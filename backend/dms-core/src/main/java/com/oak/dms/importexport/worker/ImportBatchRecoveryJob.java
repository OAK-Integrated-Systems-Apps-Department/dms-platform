package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.service.impl.BulkImportServiceImpl;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.outbox.OutboxEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Recovers bulk import batches that stopped moving. Runs every
 * {@code dms.import-export.batch-recovery-interval-ms} (default 15 min). A
 * batch's last activity is the latest created_at/updated_at of the batch and
 * its rows (see {@link ImportBatchRepository#LAST_ACTIVITY}).
 * <ul>
 *   <li>Abandoned - {@code uploading} with no activity for
 *       {@code abandoned-upload-hours} (default 24): cancelled with reason
 *       {@code expired}, and its staged files deleted after commit, as a
 *       manual cancel does. Audited as BULK_IMPORT_CANCELLED by the system
 *       actor.</li>
 *   <li>Stuck - {@code queued} or {@code running} with no activity for
 *       {@code stuck-batch-hours} (default 2). The first time, its
 *       {@code import_batch.submitted} event is published again; the worker
 *       resumes a batch safely on redelivery (logged, not audited). The
 *       window then restarts. If
 *       it is stuck again, the job gives up: rows still pending without a
 *       scan ticket fail with {@value #PROCESSING_ABANDONED}, and the batch
 *       is finalised the normal way. A stuck batch whose creator has been
 *       deleted is not re-published (it couldn't run as nobody): it goes
 *       straight to {@link ImportBatchFinaliser#finaliseWithoutCreator}. Rows waiting on a ticket are left for
 *       {@link ImportCompletionScheduler} - the scan module still creates
 *       their document on a clean verdict - so a batch with any such row
 *       stays running until they resolve.</li>
 * </ul>
 *
 * <p>Scheduled and made safe for several instances the same way as
 * {@link RetainedImportFilePurger}: the candidate list isn't locked, but
 * each batch is claimed with {@code FOR UPDATE SKIP LOCKED} in its own
 * transaction and re-checked (status and last activity) before anything
 * changes.
 *
 * <p>Known limits: a batch waiting in the import queue for longer than
 * twice {@code stuck-batch-hours} is given up on, so set that above the
 * longest expected queue wait. A single scan-gate call hanging for longer
 * than the window could race the give-up - any row write resets the window,
 * so only a call stalled that long can.
 */
@Slf4j
@Component
public class ImportBatchRecoveryJob {

    /** Batches per candidate query; each holds at most max-files-per-batch items. */
    static final int CHUNK_SIZE = 50;
    static final String PROCESSING_ABANDONED = "PROCESSING_ABANDONED";
    static final String PROCESSING_ABANDONED_MESSAGE =
            "Processing stopped unexpectedly. Upload these files again in a new import.";
    private static final List<ImportBatchStatus> STUCK_STATUSES =
            List.of(ImportBatchStatus.queued, ImportBatchStatus.running);

    private final ImportBatchRepository importBatchRepository;
    private final ImportItemRepository importItemRepository;
    private final StagedImportFiles stagedImportFiles;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ImportBatchFinaliser finaliser;
    private final ImportExportProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public ImportBatchRecoveryJob(ImportBatchRepository importBatchRepository,
                                  ImportItemRepository importItemRepository,
                                  StagedImportFiles stagedImportFiles,
                                  OutboxEventPublisher outboxEventPublisher,
                                  ImportBatchFinaliser finaliser,
                                  ImportExportProperties properties,
                                  ObjectMapper objectMapper,
                                  AuditService auditService,
                                  PlatformTransactionManager transactionManager) {
        this.importBatchRepository = importBatchRepository;
        this.importItemRepository = importItemRepository;
        this.stagedImportFiles = stagedImportFiles;
        this.outboxEventPublisher = outboxEventPublisher;
        this.finaliser = finaliser;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${dms.import-export.batch-recovery-interval-ms:900000}",
            initialDelayString = "${dms.import-export.batch-recovery-interval-ms:900000}")
    public void recoverBatches() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime abandonedCutoff = now.minusHours(properties.getAbandonedUploadHours());
        sweep(List.of(ImportBatchStatus.uploading), abandonedCutoff, id -> expireAbandoned(id, abandonedCutoff));

        OffsetDateTime stuckCutoff = now.minusHours(properties.getStuckBatchHours());
        sweep(STUCK_STATUSES, stuckCutoff, id -> recoverStuck(id, stuckCutoff));
    }

    /** Runs {@code action} on every inactive batch, a chunk at a time - the purger's loop. */
    private void sweep(List<ImportBatchStatus> statuses, OffsetDateTime cutoff, Predicate<UUID> action) {
        List<String> statusNames = statuses.stream().map(Enum::name).toList();
        // A batch that is locked or fails stays a candidate; remembering what
        // this run has tried keeps it from being fetched again and again.
        Set<UUID> tried = new HashSet<>();
        while (true) {
            List<UUID> chunk = importBatchRepository.findInactiveBatchIds(statusNames, cutoff, CHUNK_SIZE);
            List<UUID> fresh = chunk.stream().filter(tried::add).toList();
            for (UUID batchId : fresh) {
                try {
                    action.test(batchId);
                } catch (RuntimeException e) {
                    log.error("Recovering bulk import batch {} failed", batchId, e);
                }
            }
            if (chunk.size() < CHUNK_SIZE || fresh.isEmpty()) {
                break;
            }
        }
    }

    /** @return whether the batch was cancelled as expired */
    boolean expireAbandoned(UUID batchId, OffsetDateTime cutoff) {
        Boolean expired = transactionTemplate.execute(status -> {
            Optional<ImportBatch> locked = lockIfInactive(batchId, List.of(ImportBatchStatus.uploading), cutoff);
            if (locked.isEmpty()) {
                return false;
            }
            ImportBatch batch = locked.get();
            batch.setStatus(ImportBatchStatus.cancelled);
            batch.setCancelReason(ImportBatch.CancelReason.expired);
            batch.setUpdatedAt(OffsetDateTime.now());
            importBatchRepository.save(batch);

            List<ImportItem> staged = importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batchId);
            int toDelete = stagedImportFiles.clearAndDeleteAfterCommit(staged);
            importItemRepository.saveAll(staged);

            // created_by is nullable, hence not Map.of.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("cancelReason", ImportBatch.CancelReason.expired.name());
            details.put("initiatedBy", batch.getCreatedBy());
            details.put("abandonedUploadHours", properties.getAbandonedUploadHours());
            auditService.event(AuditActionCatalog.BULK_IMPORT_CANCELLED)
                    .tenantId(batch.getTenantId())
                    .systemActor()
                    .objectType(AuditObjectTypes.IMPORT_BATCH)
                    .objectId(batchId)
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(details)
                    .log();

            log.info("Cancelled bulk import batch {} as expired: no uploads for {}h ({} staged files to delete)",
                    batchId, properties.getAbandonedUploadHours(), toDelete);
            return true;
        });
        return Boolean.TRUE.equals(expired);
    }

    /** What {@link #recoverStuck} did with a batch, inside its transaction. */
    private enum StuckOutcome { REPUBLISHED, GAVE_UP, CREATOR_DELETED }

    /** @return whether the batch was acted on (re-published, or given up on) */
    boolean recoverStuck(UUID batchId, OffsetDateTime cutoff) {
        StuckOutcome outcome = transactionTemplate.execute(status -> {
            Optional<ImportBatch> locked = lockIfInactive(batchId, STUCK_STATUSES, cutoff);
            if (locked.isEmpty()) {
                return null;
            }
            ImportBatch batch = locked.get();
            if (batch.getCreatedBy() == null) {
                log.info("Stuck bulk import batch {} has no creator any more; failing its rows", batchId);
                return StuckOutcome.CREATOR_DELETED;
            }
            if (batch.getRecoveryAttempts() == 0) {
                republish(batch);
                return StuckOutcome.REPUBLISHED;
            }
            giveUp(batch);
            return StuckOutcome.GAVE_UP;
        });
        // After commit, as the completion job does; finalising holds off
        // while rows still wait on a scan ticket.
        if (outcome == StuckOutcome.GAVE_UP) {
            finaliser.finalise(batchId);
        } else if (outcome == StuckOutcome.CREATOR_DELETED) {
            finaliser.finaliseWithoutCreator(batchId);
        }
        return outcome != null;
    }

    private void republish(ImportBatch batch) {
        outboxEventPublisher.publish(batch.getTenantId(), BulkImportServiceImpl.AGGREGATE_TYPE,
                batch.getImportBatchId(), BulkImportServiceImpl.SUBMITTED_EVENT_TYPE,
                BulkImportServiceImpl.submittedPayload(batch));
        batch.setRecoveryAttempts(1);
        // Restarts the stuck window: it is given up on only if nothing
        // happens for another stuck-batch-hours.
        batch.setUpdatedAt(OffsetDateTime.now());
        importBatchRepository.save(batch);
        log.info("Bulk import batch {} stuck {} for {}h; published its submitted event again", batch.getImportBatchId(),
                batch.getStatus(), properties.getStuckBatchHours());
    }

    private void giveUp(ImportBatch batch) {
        OffsetDateTime now = OffsetDateTime.now();
        ImportBatchStatus wasStatus = batch.getStatus();
        if (wasStatus == ImportBatchStatus.queued) {
            // The worker never claimed it; the finaliser only finalises a
            // running batch.
            batch.setStatus(ImportBatchStatus.running);
            if (batch.getStartedAt() == null) {
                batch.setStartedAt(now);
            }
        }

        List<ImportItem> pending = importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(
                batch.getImportBatchId(), ImportItemStatus.pending);
        List<ImportRowError> abandoned = List.of(ImportRowError.error(null, PROCESSING_ABANDONED,
                PROCESSING_ABANDONED_MESSAGE));
        int failed = 0;
        for (ImportItem item : pending) {
            if (item.getScanResultId() != null) {
                // The scan module still creates its document on a clean
                // verdict - left for the completion job.
                continue;
            }
            // Its staged file (if any) is deleted when the batch is finalised.
            item.setStatus(ImportItemStatus.failed);
            item.setValidationErrors(ImportRecordCreator.appendErrors(objectMapper, item, abandoned));
            item.setUpdatedAt(now);
            failed++;
        }
        if (failed > 0) {
            importItemRepository.saveAll(pending);
        }

        batch.setRecoveryAttempts(batch.getRecoveryAttempts() + 1);
        batch.setUpdatedAt(now);
        importBatchRepository.save(batch);
        log.info("Gave up on stuck bulk import batch {} (was {}): {} unprocessed rows failed, {} left waiting on a"
                + " scan", batch.getImportBatchId(), wasStatus, failed, pending.size() - failed);
    }

    /** The batch, locked, if it is still in one of {@code statuses} and still inactive since {@code cutoff}. */
    private Optional<ImportBatch> lockIfInactive(UUID batchId, List<ImportBatchStatus> statuses,
                                                 OffsetDateTime cutoff) {
        return importBatchRepository.lockForRecovery(batchId)
                .filter(batch -> statuses.contains(batch.getStatus()))
                .filter(batch -> importBatchRepository.isInactiveSince(batchId, cutoff));
    }
}
