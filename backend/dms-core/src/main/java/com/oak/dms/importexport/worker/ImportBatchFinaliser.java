package com.oak.dms.importexport.worker;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Brings a running batch's counters up to date and, once none of its rows
 * is still {@code pending}, gives it its final status - {@code completed}
 * if every row became a document, {@code completed_with_errors} if some
 * did, {@code failed} if none did.
 *
 * <p>Staged files are left in place. By now the only rows that still have
 * one failed on their data (rows the scan gate accepted or rejected had
 * theirs cleared then), and a resubmit of the batch's failed rows reuses
 * them. {@link RetainedImportFilePurger} deletes them once
 * {@code dms.import-export.retained-file-hours} have passed.
 *
 * <p>Runs under the batch row lock, so the worker and the completion job
 * can both call it after their passes without racing - and so
 * BULK_IMPORT_COMPLETED is recorded exactly once, in the same transaction
 * that sets the final status.
 */
@Slf4j
@Component
public class ImportBatchFinaliser {

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public ImportBatchFinaliser(ImportBatchRepository importBatchRepository,
                                ImportItemRepository importItemRepository,
                                AuditService auditService,
                                PlatformTransactionManager transactionManager) {
        this.importBatchRepository = importBatchRepository;
        this.importItemRepository = importItemRepository;
        this.auditService = auditService;
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
            ImportBatch batch = locked.get();
            long succeeded = importItemRepository.countByImportBatchIdAndStatus(batchId, ImportItemStatus.success);
            long failed = importItemRepository.countByImportBatchIdAndStatus(batchId, ImportItemStatus.failed);
            long pending = importItemRepository.countByImportBatchIdAndStatus(batchId, ImportItemStatus.pending);
            OffsetDateTime now = OffsetDateTime.now();
            batch.setSuccessCount(Math.toIntExact(succeeded));
            batch.setFailureCount(Math.toIntExact(failed));
            batch.setUpdatedAt(now);

            if (pending > 0) {
                importBatchRepository.save(batch);
                return Optional.<ImportBatchStatus>empty();
            }

            ImportBatchStatus finalStatus = succeeded == 0 ? ImportBatchStatus.failed
                    : failed == 0 ? ImportBatchStatus.completed
                    : ImportBatchStatus.completed_with_errors;
            batch.setStatus(finalStatus);
            batch.setCompletedAt(now);
            importBatchRepository.save(batch);

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

            log.info("Finished bulk import batch {}: {} ({} created, {} failed)", batchId, finalStatus, succeeded,
                    failed);
            return Optional.of(finalStatus);
        });
    }
}
