package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportRowError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Brings a running batch's counters up to date and, once none of its rows
 * is still {@code pending}, gives it its final status - {@code completed}
 * if every row became a document, {@code completed_with_errors} if some
 * did, {@code failed} if none did - and deletes whatever staged files are
 * left (rows that failed before reaching the scan gate), once the
 * transaction commits. {@link RetainedImportFilePurger} is the backstop for
 * a delete that doesn't happen.
 *
 * <p>Also finishes a batch whose creator has been deleted (see
 * {@link #finaliseWithoutCreator}): nothing can be submitted to the scan
 * gate without a user, so its unprocessed rows can only fail.
 *
 * <p>Runs under the batch row lock, so the worker and the completion job
 * can both call it after their passes without racing - and so
 * BULK_IMPORT_COMPLETED is recorded exactly once, in the same transaction
 * that sets the final status.
 */
@Slf4j
@Component
public class ImportBatchFinaliser {

    static final String CREATOR_DELETED = "CREATOR_DELETED";
    static final String CREATOR_DELETED_MESSAGE = "The user who started this import no longer exists.";

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final StagedImportFiles stagedImportFiles;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ImportBatchFinaliser(ImportBatchRepository importBatchRepository,
                                ImportItemRepository importItemRepository,
                                StagedImportFiles stagedImportFiles,
                                AuditService auditService,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.importBatchRepository = importBatchRepository;
        this.importItemRepository = importItemRepository;
        this.stagedImportFiles = stagedImportFiles;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * @return the batch's final status if this call finalised it; empty if
     *         it isn't running (gone, or already finalised) or still has
     *         pending rows
     */
    public Optional<ImportBatchStatus> finalise(UUID batchId) {
        return transactionTemplate.execute(status -> {
            Optional<ImportBatch> locked = importBatchRepository.lockByImportBatchId(batchId);
            if (locked.isEmpty() || locked.get().getStatus() != ImportBatchStatus.running) {
                return Optional.<ImportBatchStatus>empty();
            }
            return finaliseLocked(locked.get());
        });
    }

    /**
     * For a {@code queued} or {@code running} batch whose {@code created_by}
     * is null - its creator was deleted. Every pending row the scan gate
     * hasn't accepted fails with {@value #CREATOR_DELETED}, without any
     * user being bound to TenantContext; rows waiting on a scan ticket are
     * left to the completion job (in practice there are none: a user whose
     * files reached the gate can't be deleted). The batch is then finalised
     * as {@link #finalise} does.
     *
     * @return as {@link #finalise}; empty too if the batch isn't in that state
     */
    public Optional<ImportBatchStatus> finaliseWithoutCreator(UUID batchId) {
        return transactionTemplate.execute(status -> {
            Optional<ImportBatch> locked = importBatchRepository.lockByImportBatchId(batchId);
            if (locked.isEmpty() || locked.get().getCreatedBy() != null) {
                return Optional.<ImportBatchStatus>empty();
            }
            ImportBatch batch = locked.get();
            OffsetDateTime now = OffsetDateTime.now();
            switch (batch.getStatus()) {
                case queued -> {
                    // Never claimed; only a running batch is finalised.
                    batch.setStatus(ImportBatchStatus.running);
                    if (batch.getStartedAt() == null) {
                        batch.setStartedAt(now);
                    }
                }
                case running -> {
                }
                default -> {
                    return Optional.<ImportBatchStatus>empty();
                }
            }

            List<ImportItem> pending = importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(
                    batchId, ImportItemStatus.pending);
            List<ImportRowError> creatorDeleted = List.of(ImportRowError.error(null, CREATOR_DELETED,
                    CREATOR_DELETED_MESSAGE));
            int failed = 0;
            for (ImportItem item : pending) {
                if (item.getScanResultId() != null) {
                    continue;
                }
                item.setStatus(ImportItemStatus.failed);
                item.setValidationErrors(ImportRecordCreator.appendErrors(objectMapper, item, creatorDeleted));
                item.setUpdatedAt(now);
                failed++;
            }
            if (failed > 0) {
                importItemRepository.saveAll(pending);
            }
            log.info("Creator of bulk import batch {} no longer exists: {} unprocessed rows failed", batchId, failed);
            return finaliseLocked(batch);
        });
    }

    private Optional<ImportBatchStatus> finaliseLocked(ImportBatch batch) {
        UUID batchId = batch.getImportBatchId();
        long succeeded = importItemRepository.countByImportBatchIdAndStatus(batchId, ImportItemStatus.success);
        long failed = importItemRepository.countByImportBatchIdAndStatus(batchId, ImportItemStatus.failed);
        long pending = importItemRepository.countByImportBatchIdAndStatus(batchId, ImportItemStatus.pending);
        OffsetDateTime now = OffsetDateTime.now();
        batch.setSuccessCount(Math.toIntExact(succeeded));
        batch.setFailureCount(Math.toIntExact(failed));
        batch.setUpdatedAt(now);

        if (pending > 0) {
            importBatchRepository.save(batch);
            return Optional.empty();
        }

        ImportBatchStatus finalStatus = succeeded == 0 ? ImportBatchStatus.failed
                : failed == 0 ? ImportBatchStatus.completed
                : ImportBatchStatus.completed_with_errors;
        batch.setStatus(finalStatus);
        batch.setCompletedAt(now);
        importBatchRepository.save(batch);

        // Nothing reuses a failed row's file any more, so it goes now.
        List<ImportItem> staged = importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batchId);
        int toDelete = 0;
        if (!staged.isEmpty()) {
            toDelete = stagedImportFiles.clearAndDeleteAfterCommit(staged);
            importItemRepository.saveAll(staged);
        }

        // AuditOutcome has no "partial": a batch that created any
        // documents succeeded; the status and counts say how well.
        // created_by is nullable, hence not Map.of.
        boolean noneImported = finalStatus == ImportBatchStatus.failed;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", finalStatus.name());
        details.put("successCount", succeeded);
        details.put("failureCount", failed);
        details.put("initiatedBy", batch.getCreatedBy());
        auditService.event(AuditActionCatalog.BULK_IMPORT_COMPLETED)
                .tenantId(batch.getTenantId())
                .systemActor()
                .objectType(AuditObjectTypes.IMPORT_BATCH)
                .objectId(batchId)
                .outcome(noneImported ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS)
                .failureReason(noneImported ? "No rows were imported" : null)
                .newValue(details)
                .log();

        log.info("Finished bulk import batch {}: {} ({} created, {} failed, {} staged files to delete)", batchId,
                finalStatus, succeeded, failed, toDelete);
        return Optional.of(finalStatus);
    }
}
