package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.messaging.BulkImportMessagingConfig;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.importexport.validation.ImportRowValidator;
import com.oak.dms.importexport.validation.ImportRowValidator.RowValues;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.security.auth.JobIdentity;
import com.oak.dms.security.auth.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@code import_batch.submitted} and processes the batch in two
 * passes:
 * <ol>
 *   <li>validation - every item is checked by {@link ImportRowValidator};
 *       failing rows are marked {@code failed} with their errors, passing
 *       rows stay {@code pending} (with any warnings);</li>
 *   <li>record creation - each passing row's file goes through the scan
 *       gate one at a time, and becomes a document with the row's metadata
 *       once clean (see {@link ImportRecordCreator}). Rows whose scan is
 *       still running stay {@code pending} for
 *       {@link ImportCompletionScheduler}.</li>
 * </ol>
 * The batch is then finalised by {@link ImportBatchFinaliser} if no row is
 * left pending; otherwise the completion job finalises it later.
 *
 * <p>Work runs outside any web request, the way scanning's background work
 * does: short {@link TransactionTemplate} transactions, the batch row
 * locked while its status changes. The batch is claimed in its own
 * transaction, then items are validated in chunks of {@value #CHUNK_SIZE},
 * one transaction each, so progress survives a crash part-way through. The
 * scan gate commits its own steps, so it is never called inside one.
 *
 * <p>Idempotency: a redelivered message for a batch already {@code running}
 * (the previous attempt crashed, or two deliveries raced) processes it
 * again. Rows already {@code success} are skipped by both passes; rows with
 * a {@code scan_result_id} are not re-validated and have their ticket
 * re-checked with {@code IngestionGate#getTicket}; only rows with neither
 * are submitted to the gate. Known limitation: the gate has no idempotency
 * key, so a crash after the gate accepted a file but before its
 * {@code scan_result_id} was saved submits that one file again on
 * redelivery, and it may end up as two documents.
 *
 * <p>Unexpected exceptions propagate: the container retries the message a
 * few times, then dead-letters it (see {@link BulkImportMessagingConfig}).
 * A gate rejection of a file (too large, type not allowed) only fails its
 * row. A batch whose message ends up on the DLQ is left {@code running},
 * with whatever was done saved; its rows the gate already accepted are
 * still resolved by the completion job, but rows never submitted stay
 * pending and the batch is not finalised.
 */
@Slf4j
@Component
public class BulkImportWorker {

    static final int CHUNK_SIZE = 50;

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final ImportSchemaFields importSchemaFields;
    private final ImportRowValidator rowValidator;
    private final ImportRecordCreator recordCreator;
    private final ImportBatchFinaliser finaliser;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final JobIdentity jobIdentity;

    public BulkImportWorker(ImportBatchRepository importBatchRepository,
                            ImportItemRepository importItemRepository,
                            ImportSchemaFields importSchemaFields,
                            ImportRowValidator rowValidator,
                            ImportRecordCreator recordCreator,
                            ImportBatchFinaliser finaliser,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager,
                            JobIdentity jobIdentity) {
        this.importBatchRepository = importBatchRepository;
        this.importItemRepository = importItemRepository;
        this.importSchemaFields = importSchemaFields;
        this.rowValidator = rowValidator;
        this.recordCreator = recordCreator;
        this.finaliser = finaliser;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jobIdentity = jobIdentity;
    }

    /** What processing needs from the claimed batch, read while it was locked. */
    /** @param folderId where the batch's documents are filed; null = not in a folder */
    /** @param duplicateAction keep_both, new_version or skip - see ImportBatch#duplicateAction */
    record ClaimedBatch(UUID importBatchId, UUID tenantId, UUID createdBy, UUID documentTypeId, int schemaVersion,
                        UUID folderId, String duplicateAction) {

        ClaimedBatch(UUID importBatchId, UUID tenantId, UUID createdBy, UUID documentTypeId, int schemaVersion) {
            this(importBatchId, tenantId, createdBy, documentTypeId, schemaVersion, null, "keep_both");
        }

        ClaimedBatch(UUID importBatchId, UUID tenantId, UUID createdBy, UUID documentTypeId, int schemaVersion,
                     UUID folderId) {
            this(importBatchId, tenantId, createdBy, documentTypeId, schemaVersion, folderId, "keep_both");
        }
    }

    @RabbitListener(queues = BulkImportMessagingConfig.QUEUE,
            containerFactory = BulkImportMessagingConfig.LISTENER_CONTAINER_FACTORY)
    public void onMessage(Message message) {
        UUID batchId = parseBatchId(message.getBody());
        claim(batchId).ifPresent(this::process);
    }

    /**
     * The batch id from an {@code import_batch.submitted} body. OutboxRelay
     * sends the outbox row's payload - already a JSON string - through
     * Jackson2JsonMessageConverter, which encodes it again, so today's body
     * is a JSON string literal whose content is the payload object:
     * {@code "{\"importBatchId\":\"...\",\"correlationId\":\"...\"}"}.
     * A plain JSON object body is accepted too, so this keeps working if
     * the relay stops double-encoding.
     *
     * @throws AmqpRejectAndDontRequeueException if the body isn't a payload
     *         with a valid importBatchId - sent to the DLQ without retrying
     */
    UUID parseBatchId(byte[] body) {
        try {
            JsonNode payload = objectMapper.readTree(body);
            if (payload != null && payload.isTextual()) {
                payload = objectMapper.readTree(payload.asText());
            }
            JsonNode id = payload != null ? payload.get("importBatchId") : null;
            if (id == null || !id.isTextual()) {
                throw new IllegalArgumentException("no importBatchId");
            }
            return UUID.fromString(id.asText());
        } catch (IOException | IllegalArgumentException e) {
            log.error("Rejecting malformed {} message: {}", BulkImportMessagingConfig.ROUTING_KEY, e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Malformed import_batch.submitted payload", e);
        }
    }

    /**
     * Locks the batch and moves it from queued to running. Returns empty -
     * nothing to process - if it's gone or in any other state (cancelled
     * before the worker got to it, or already finished).
     */
    Optional<ClaimedBatch> claim(UUID batchId) {
        return transactionTemplate.execute(status -> {
            Optional<ImportBatch> locked = importBatchRepository.lockByImportBatchId(batchId);
            if (locked.isEmpty()) {
                log.warn("Bulk import batch {} not found; skipping", batchId);
                return Optional.<ClaimedBatch>empty();
            }
            ImportBatch batch = locked.get();
            switch (batch.getStatus()) {
                case queued -> {
                    OffsetDateTime now = OffsetDateTime.now();
                    batch.setStatus(ImportBatchStatus.running);
                    batch.setStartedAt(now);
                    batch.setUpdatedAt(now);
                    importBatchRepository.save(batch);
                    log.info("Started validating bulk import batch {}", batchId);
                }
                case running -> log.info("Bulk import batch {} is already running; resuming (redelivery)", batchId);
                default -> {
                    log.info("Bulk import batch {} is {}; skipping", batchId, batch.getStatus());
                    return Optional.<ClaimedBatch>empty();
                }
            }
            return Optional.of(new ClaimedBatch(batch.getImportBatchId(), batch.getTenantId(), batch.getCreatedBy(),
                    batch.getDocumentTypeId(), batch.getSchemaVersion(), batch.getFolderId(),
                    batch.getDuplicateAction()));
        });
    }

    /**
     * Validates every item, creates records for the passing ones and
     * finalises the batch, as the batch's creator in the batch's tenant.
     */
    void process(ClaimedBatch batch) {
        // As the creator, with their access as it is now - each row re-checks the target folder.
        if (!jobIdentity.bind(batch.tenantId(), batch.createdBy())) {
            log.warn("Bulk import batch {}: its creator is no longer an active user - failing its rows",
                    batch.importBatchId());
            for (ImportItem item : importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.importBatchId())) {
                recordCreator.failAccessRevoked(item.getImportItemId(),
                        "The person who started this import is no longer an active user");
            }
            finaliser.finalise(batch.importBatchId());
            return;
        }
        try {
            Map<String, ImportField> fields = importSchemaFields.load(batch.tenantId(), batch.documentTypeId(),
                    batch.schemaVersion());
            List<ImportItem> items = importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(
                    batch.importBatchId());
            // Filenames never change after the batch is created, so the
            // duplicate check can be worked out once for the whole batch.
            Map<String, List<Integer>> duplicates = ImportRowValidator.duplicateRows(items);
            List<UUID> itemIds = items.stream().map(ImportItem::getImportItemId).toList();

            for (int from = 0; from < itemIds.size(); from += CHUNK_SIZE) {
                List<UUID> chunk = itemIds.subList(from, Math.min(from + CHUNK_SIZE, itemIds.size()));
                transactionTemplate.executeWithoutResult(status -> validateChunk(batch, chunk, fields, duplicates));
            }

            // One row at a time, in row order, outside any transaction.
            for (UUID itemId : itemIds) {
                recordCreator.createRecord(batch, itemId, fields);
            }

            finaliser.finalise(batch.importBatchId());
        } finally {
            TenantContext.clear();
        }
    }

    private void validateChunk(ClaimedBatch batch, List<UUID> itemIds, Map<String, ImportField> fields,
                               Map<String, List<Integer>> duplicates) {
        List<ImportItem> items = importItemRepository.findAllByImportBatchIdAndImportItemIdIn(
                batch.importBatchId(), itemIds);
        OffsetDateTime now = OffsetDateTime.now();
        for (ImportItem item : items) {
            if (item.getStatus() == ImportItemStatus.success || item.getScanResultId() != null) {
                // Already a record, or already handed to the scan gate (and
                // its staged file gone) - never re-judge it.
                continue;
            }
            List<ImportRowError> errors = rowValidator.validate(item, rowValues(item), fields, duplicates,
                    batch.documentTypeId(), batch.schemaVersion(), batch.tenantId());
            if (ImportRowValidator.hasErrors(errors)) {
                item.setStatus(ImportItemStatus.failed);
                item.setValidationErrors(toJson(errors));
            } else {
                // Converted values aren't kept: record creation reconverts
                // row_values with the same converter.
                item.setStatus(ImportItemStatus.pending);
                item.setValidationErrors(errors.isEmpty() ? null : toJson(errors));
            }
            item.setUpdatedAt(now);
        }
        importItemRepository.saveAll(items);
    }

    private RowValues rowValues(ImportItem item) {
        try {
            return objectMapper.readValue(item.getRowValues(), RowValues.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable row_values on import item " + item.getImportItemId(), e);
        }
    }

    private String toJson(List<ImportRowError> errors) {
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize import row errors", e);
        }
    }
}
