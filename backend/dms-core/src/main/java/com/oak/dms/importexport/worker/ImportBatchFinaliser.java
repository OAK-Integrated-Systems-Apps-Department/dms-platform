package com.oak.dms.importexport.worker;

import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.service.BulkImportAuditor;
import com.oak.dms.importexport.storage.StagedImportFiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Brings a running batch's counters up to date and, once none of its rows
 * is still {@code pending}, gives it its final status - {@code completed}
 * if every row became a document, {@code completed_with_errors} if some
 * did, {@code failed} if none did - and deletes whatever staged files are
 * left (rows that failed before reaching the scan gate).
 *
 * <p>Runs under the batch row lock, so the worker and the completion job
 * can both call it after their passes without racing.
 */
@Slf4j
@Component
public class ImportBatchFinaliser {

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final StagedImportFiles stagedImportFiles;
    private final BulkImportAuditor auditor;
    private final TransactionTemplate transactionTemplate;

    public ImportBatchFinaliser(ImportBatchRepository importBatchRepository,
                                ImportItemRepository importItemRepository,
                                StagedImportFiles stagedImportFiles,
                                BulkImportAuditor auditor,
                                PlatformTransactionManager transactionManager) {
        this.importBatchRepository = importBatchRepository;
        this.importItemRepository = importItemRepository;
        this.stagedImportFiles = stagedImportFiles;
        this.auditor = auditor;
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
            auditor.finished(batch);

            List<ImportItem> staged = importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batchId);
            if (!staged.isEmpty()) {
                stagedImportFiles.clearAndDeleteAfterCommit(staged);
                importItemRepository.saveAll(staged);
            }

            log.info("Finished bulk import batch {}: {} ({} created, {} failed)", batchId, finalStatus, succeeded,
                    failed);
            return Optional.of(finalStatus);
        });
    }
}
