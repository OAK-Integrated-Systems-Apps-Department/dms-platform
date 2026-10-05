package com.oak.dms.importexport.worker;

import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Backstop for staged files still left on a finished batch - the
 * finaliser deletes them when the batch finishes (see
 * {@link ImportBatchFinaliser}), but that delete is best-effort and after
 * commit. Deletes any left once
 * {@code dms.import-export.retained-file-hours} (default 24) have passed
 * since the batch completed. Runs every
 * {@code dms.import-export.retained-file-purge-interval-ms} (default 1h).
 *
 * <p>Scheduled the same way as {@link ImportCompletionScheduler}, and safe
 * to run on several instances at once the same way: the candidate list
 * isn't locked, but each batch is claimed with {@code FOR UPDATE SKIP
 * LOCKED} and re-checked before anything is cleared. Objects are deleted
 * best-effort after commit, via {@link StagedImportFiles}.
 *
 * <p>Batches abandoned while uploading, or stuck queued / running, are
 * handled by {@link ImportBatchRecoveryJob}: an abandoned batch's files go
 * when it is cancelled, and a stuck batch is finalised, which deletes its
 * files like any other's.
 */
@Slf4j
@Component
public class RetainedImportFilePurger {

    /** Batches per candidate query; each holds at most max-files-per-batch items. */
    static final int CHUNK_SIZE = 50;
    static final List<ImportBatchStatus> FINISHED_STATUSES =
            List.of(ImportBatchStatus.completed, ImportBatchStatus.completed_with_errors, ImportBatchStatus.failed);

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final StagedImportFiles stagedImportFiles;
    private final ImportExportProperties properties;
    private final TransactionTemplate transactionTemplate;

    public RetainedImportFilePurger(ImportBatchRepository importBatchRepository,
                                    ImportItemRepository importItemRepository,
                                    StagedImportFiles stagedImportFiles,
                                    ImportExportProperties properties,
                                    PlatformTransactionManager transactionManager) {
        this.importBatchRepository = importBatchRepository;
        this.importItemRepository = importItemRepository;
        this.stagedImportFiles = stagedImportFiles;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${dms.import-export.retained-file-purge-interval-ms:3600000}",
            initialDelayString = "${dms.import-export.retained-file-purge-interval-ms:3600000}")
    public void purgeExpiredFiles() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(properties.getRetainedFileHours());
        // A batch that is locked or fails stays a candidate; remembering what
        // this run has tried keeps it from being fetched again and again.
        Set<UUID> tried = new HashSet<>();
        int purgedFiles = 0;

        while (true) {
            List<UUID> chunk = importBatchRepository.findBatchIdsWithExpiredStagedFiles(FINISHED_STATUSES, cutoff,
                    PageRequest.of(0, CHUNK_SIZE));
            List<UUID> fresh = chunk.stream().filter(tried::add).toList();
            for (UUID batchId : fresh) {
                try {
                    purgedFiles += purgeBatch(batchId, cutoff);
                } catch (RuntimeException e) {
                    log.error("Purging left-over staged files of bulk import batch {} failed", batchId, e);
                }
            }
            if (chunk.size() < CHUNK_SIZE || fresh.isEmpty()) {
                break;
            }
        }

        if (purgedFiles > 0) {
            log.info("Purged {} left-over staged import files older than {}h", purgedFiles,
                    properties.getRetainedFileHours());
        }
    }

    /** @return how many staged files were scheduled for deletion */
    int purgeBatch(UUID batchId, OffsetDateTime cutoff) {
        Integer purged = transactionTemplate.execute(status -> {
            Optional<ImportBatch> locked = importBatchRepository.lockForRetainedFilePurge(batchId);
            if (locked.isEmpty() || !expired(locked.get(), cutoff)) {
                return 0;
            }
            List<ImportItem> staged = importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batchId);
            if (staged.isEmpty()) {
                return 0;
            }
            int toDelete = stagedImportFiles.clearAndDeleteAfterCommit(staged);
            importItemRepository.saveAll(staged);
            log.debug("Purging {} left-over staged files of bulk import batch {}", toDelete, batchId);
            return toDelete;
        });
        return purged != null ? purged : 0;
    }

    private static boolean expired(ImportBatch batch, OffsetDateTime cutoff) {
        return FINISHED_STATUSES.contains(batch.getStatus())
                && batch.getCompletedAt() != null
                && batch.getCompletedAt().isBefore(cutoff);
    }
}
