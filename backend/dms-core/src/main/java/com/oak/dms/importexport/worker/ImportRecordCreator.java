package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportDefaults;
import com.oak.dms.importexport.validation.ImportFilenames;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.importexport.validation.ImportTitles;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadata.FieldValue;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns one validated import row into a document: its staged file goes
 * through the malware scan gate ({@link DocumentService#uploadDocument}),
 * titled after its filename, and once the gate reports the document
 * created, the batch's defaults (see {@link ImportDefaults}) are written as
 * the document's metadata, with src {@value #SOURCE}, and the row is marked
 * {@code success}.
 * Used by {@link BulkImportWorker} straight after validation and by
 * {@link ImportCompletionScheduler} for scans that finish later.
 *
 * <p>Callers bind {@code TenantContext} to the batch's tenant and creator:
 * the gate records the creator as the uploader, and only hands the ticket
 * back to that same user. The gate commits its own steps, so it is always
 * called outside a transaction; everything this class writes happens in
 * short transactions of its own.
 *
 * <p>Outcomes are appended to the row's {@code validation_errors}, so
 * warnings from validation are kept.
 */
@Slf4j
@Component
public class ImportRecordCreator {

    static final String SOURCE = "import";
    static final String SCAN_REJECTED = "SCAN_REJECTED";
    static final String FILE_MISSING = "FILE_MISSING";
    private static final String FILE = "file";
    private static final TypeReference<List<ImportRowError>> ERRORS = new TypeReference<>() {
    };

    private final DocumentService documentService;
    private final IngestionGate ingestionGate;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final ImportItemRepository importItemRepository;
    private final StagedImportFiles stagedImportFiles;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ImportRecordCreator(DocumentService documentService,
                               IngestionGate ingestionGate,
                               DocumentMetadataRepository documentMetadataRepository,
                               ImportItemRepository importItemRepository,
                               StagedImportFiles stagedImportFiles,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.documentService = documentService;
        this.ingestionGate = ingestionGate;
        this.documentMetadataRepository = documentMetadataRepository;
        this.importItemRepository = importItemRepository;
        this.stagedImportFiles = stagedImportFiles;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Takes one row as far as it can go now. A row that already failed or
     * succeeded is left alone; a row the gate has already accepted has its
     * ticket re-checked rather than being submitted again; anything else
     * is submitted. A scan that is still running leaves the row
     * {@code pending} for {@link ImportCompletionScheduler}.
     *
     * @param defaults the batch's defaults - the metadata every document gets
     * @throws RuntimeException anything other than the gate rejecting the
     *                          file - the caller retries the whole batch
     */
    public void createRecord(BulkImportWorker.ClaimedBatch batch, UUID itemId, ImportDefaults.Resolved defaults) {
        Optional<ImportItem> found = importItemRepository.findById(itemId);
        if (found.isEmpty() || found.get().getStatus() != ImportItemStatus.pending) {
            return;
        }
        ImportItem item = found.get();

        IngestionTicketResponse ticket;
        if (item.getScanResultId() != null) {
            // Accepted on an earlier attempt - never hand the file over twice.
            ticket = ingestionGate.getTicket(item.getScanResultId());
        } else {
            Optional<IngestionTicketResponse> submitted = submit(batch, item);
            if (submitted.isEmpty()) {
                return;
            }
            ticket = submitted.get();
        }
        handleTicket(batch, itemId, ticket, defaults);
    }

    /**
     * Applies a ticket to its row, under a {@code SKIP LOCKED} row lock: if
     * another worker holds the row, or it has already been resolved,
     * nothing happens.
     */
    public void handleTicket(BulkImportWorker.ClaimedBatch batch, UUID itemId, IngestionTicketResponse ticket,
                             ImportDefaults.Resolved defaults) {
        transactionTemplate.executeWithoutResult(status -> importItemRepository.lockPendingForCompletion(itemId)
                .ifPresent(item -> apply(batch, item, ticket, defaults)));
    }

    /** The gate's ticket, or empty if the gate rejected the file (the row is then failed). */
    private Optional<IngestionTicketResponse> submit(BulkImportWorker.ClaimedBatch batch, ImportItem item) {
        String filename = ImportFilenames.strip(item.getFilenameReference());
        String title = ImportTitles.fromFilename(filename);

        InputStream content;
        try {
            content = stagedImportFiles.open(item).content();
        } catch (StorageObjectNotFoundException e) {
            log.warn("Staged file for row {} of bulk import batch {} is missing", item.getRowNumber(),
                    batch.importBatchId());
            fail(item.getImportItemId(), FILE_MISSING,
                    "The uploaded file could not be found - upload it again in a new import");
            return Optional.empty();
        }

        IngestionTicketResponse ticket;
        try {
            ticket = documentService.uploadDocument(batch.documentTypeId(), title, filename,
                    item.getStagedSizeBytes(), content);
        } catch (ApiException e) {
            if (!isRejection(e)) {
                throw e;
            }
            log.info("Scan gate rejected row {} of bulk import batch {}: {}", item.getRowNumber(),
                    batch.importBatchId(), e.getMessage());
            fail(item.getImportItemId(), SCAN_REJECTED, e.getMessage());
            return Optional.empty();
        } finally {
            closeQuietly(content, item);
        }

        // The gate now holds its own copy: record the ticket at once, and
        // drop the staged file once that has committed.
        transactionTemplate.executeWithoutResult(status -> {
            ImportItem accepted = importItemRepository.findById(item.getImportItemId())
                    .orElseThrow(() -> new IllegalStateException("Import item " + item.getImportItemId()
                            + " disappeared"));
            accepted.setScanResultId(ticket.scanResultId());
            stagedImportFiles.clearAndDeleteAfterCommit(List.of(accepted));
            accepted.setUpdatedAt(OffsetDateTime.now());
            importItemRepository.save(accepted);
        });
        return Optional.of(ticket);
    }

    /**
     * A 4xx from the gate is about the file itself (too large, a type that
     * isn't allowed, empty, content not matching its extension) - trying
     * again won't change it. Anything else is the platform's problem.
     */
    private static boolean isRejection(ApiException e) {
        return e.getStatus() != null && e.getStatus().is4xxClientError();
    }

    private void apply(BulkImportWorker.ClaimedBatch batch, ImportItem item, IngestionTicketResponse ticket,
                       ImportDefaults.Resolved defaults) {
        switch (ticket.status()) {
            case clean, released_override -> {
                if (ticket.documentId() == null) {
                    // Not promoted yet - check again on the next pass.
                    return;
                }
                complete(batch, item, ticket.documentId(), defaults);
            }
            case pending -> {
                // Still scanning (or waiting to retry) - the completion job
                // picks it up again.
            }
            case infected, quarantined -> markFailed(item, SCAN_REJECTED,
                    "Malware detected: " + firstNonBlank(ticket.threatName(), ticket.message(), "unknown threat"));
            case unscannable, failed -> markFailed(item, SCAN_REJECTED,
                    ticket.message() == null || ticket.message().isBlank()
                            ? "File could not be scanned"
                            : "File could not be scanned: " + ticket.message());
        }
    }

    private void complete(BulkImportWorker.ClaimedBatch batch, ImportItem item, UUID documentId,
                          ImportDefaults.Resolved defaults) {
        // Defaults that didn't convert were already reported on the row
        // (DEFAULT_VALUE_INVALID) when it was validated.
        Map<String, FieldValue> values = new LinkedHashMap<>();
        for (ImportDefaults.AppliedDefault applied : defaults.applied()) {
            values.put(applied.field().fieldId().toString(), new FieldValue(applied.value(), SOURCE, null));
        }
        writeMetadata(batch, documentId, values);

        item.setStatus(ImportItemStatus.success);
        item.setDocumentId(documentId);
        item.setUpdatedAt(OffsetDateTime.now());
        importItemRepository.save(item);
    }

    /**
     * One document_metadata row per document. If one exists already (an
     * earlier attempt wrote it, then crashed before marking the row), the
     * values are merged into it rather than inserted again.
     */
    private void writeMetadata(BulkImportWorker.ClaimedBatch batch, UUID documentId, Map<String, FieldValue> values) {
        DocumentMetadataId id = new DocumentMetadataId(batch.tenantId(), documentId);
        DocumentMetadata metadata = documentMetadataRepository.findById(id)
                .map(existing -> {
                    Map<String, FieldValue> merged = new HashMap<>(existing.getFieldValues());
                    merged.putAll(values);
                    existing.setFieldValues(merged);
                    existing.setDocumentTypeId(batch.documentTypeId());
                    existing.setSchemaVersion(batch.schemaVersion());
                    existing.setUpdatedBy(batch.createdBy());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    return existing;
                })
                .orElseGet(() -> DocumentMetadata.builder()
                        .id(id)
                        .documentTypeId(batch.documentTypeId())
                        .schemaVersion(batch.schemaVersion())
                        .fieldValues(new HashMap<>(values))
                        .createdBy(batch.createdBy())
                        .build());
        documentMetadataRepository.save(metadata);
    }

    private void fail(UUID itemId, String code, String message) {
        transactionTemplate.executeWithoutResult(status -> importItemRepository.findById(itemId)
                .ifPresent(item -> markFailed(item, code, message)));
    }

    private void markFailed(ImportItem item, String code, String message) {
        item.setStatus(ImportItemStatus.failed);
        item.setValidationErrors(appendErrors(item, List.of(ImportRowError.error(FILE, code, message))));
        item.setUpdatedAt(OffsetDateTime.now());
        importItemRepository.save(item);
    }

    private String appendErrors(ImportItem item, List<ImportRowError> added) {
        return appendErrors(objectMapper, item, added);
    }

    /** The item's validation_errors JSON with {@code added} after what is already there. */
    static String appendErrors(ObjectMapper objectMapper, ImportItem item, List<ImportRowError> added) {
        List<ImportRowError> errors = new ArrayList<>();
        try {
            if (item.getValidationErrors() != null) {
                errors.addAll(objectMapper.readValue(item.getValidationErrors(), ERRORS));
            }
            errors.addAll(added);
            return objectMapper.writeValueAsString(errors);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable validation_errors on import item " + item.getImportItemId(), e);
        }
    }

    private static void closeQuietly(InputStream content, ImportItem item) {
        try {
            content.close();
        } catch (IOException e) {
            log.warn("Failed to close staged file stream for import item {}", item.getImportItemId(), e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
