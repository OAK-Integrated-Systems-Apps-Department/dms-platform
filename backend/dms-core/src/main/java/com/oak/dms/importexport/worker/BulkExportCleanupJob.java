package com.oak.dms.importexport.worker;

import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.importexport.service.impl.BulkExportServiceImpl;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Housekeeping for bulk exports, every
 * {@code dms.import-export.export-cleanup-interval-ms} (default 15 min):
 * <ol>
 *   <li>expiry warning - a completed export due to expire within
 *       {@code export-expiry-warning-hours} (default 2) gets
 *       {@code export_job.expiring_soon} published, once
 *       ({@code expiry_warning_sent_at} records it), for the notification
 *       module;</li>
 *   <li>expiry - a completed export past {@code expires_at} becomes
 *       {@code expired}, and its ZIP is deleted after commit
 *       (best-effort, like bulk import's staged files);</li>
 *   <li>stuck jobs - a pending or processing export with no activity for
 *       {@code stuck-export-hours} (default 2) is failed. Activity means
 *       updated_at, which {@link BulkExportWorker} bumps at every stage and
 *       at least every 50 documents - never the job's age, so a long
 *       export that is still moving is left alone. This is also where a
 *       job whose message was dead-lettered ends up.</li>
 * </ol>
 * Safe to run on several instances at once, the same way as
 * {@link RetainedImportFilePurger}: the candidate lists aren't locked, but
 * each job is claimed with {@code FOR UPDATE SKIP LOCKED} and re-checked
 * under the lock before anything changes.
 */
@Slf4j
@Component
public class BulkExportCleanupJob {

    static final int CHUNK_SIZE = 50;
    static final List<ExportJobStatus> UNFINISHED_STATUSES =
            List.of(ExportJobStatus.pending, ExportJobStatus.processing);
    static final String REASON_STUCK = "The export did not finish in time. Please request it again.";

    private final ExportJobRepository exportJobRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final OutboxEventPublisher outboxEventPublisher;
    private final BulkExportCompletion completion;
    private final ImportExportProperties properties;
    private final TransactionTemplate transactionTemplate;

    public BulkExportCleanupJob(ExportJobRepository exportJobRepository,
                                StorageLocationRepository storageLocationRepository,
                                ObjectStorageServiceFactory objectStorageServiceFactory,
                                OutboxEventPublisher outboxEventPublisher,
                                BulkExportCompletion completion,
                                ImportExportProperties properties,
                                PlatformTransactionManager transactionManager) {
        this.exportJobRepository = exportJobRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.objectStorageServiceFactory = objectStorageServiceFactory;
        this.outboxEventPublisher = outboxEventPublisher;
        this.completion = completion;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${dms.import-export.export-cleanup-interval-ms:900000}",
            initialDelayString = "${dms.import-export.export-cleanup-interval-ms:900000}")
    public void cleanUp() {
        OffsetDateTime now = OffsetDateTime.now();
        int warned = sweep(page -> exportJobRepository.findExportIdsDueExpiryWarning(now,
                now.plusHours(properties.getExportExpiryWarningHours()), page), id -> warn(id, now));
        int expired = sweep(page -> exportJobRepository.findExpiredExportIds(now, page), id -> expire(id, now));
        OffsetDateTime stuckCutoff = now.minusHours(properties.getStuckExportHours());
        int failed = sweep(page -> exportJobRepository.findInactiveExportIds(UNFINISHED_STATUSES, stuckCutoff, page),
                id -> failStuck(id, stuckCutoff));
        if (warned + expired + failed > 0) {
            log.info("Bulk export cleanup: {} expiry warnings, {} expired, {} stuck jobs failed", warned, expired,
                    failed);
        }
    }

    /**
     * Runs {@code action} on every candidate, a chunk at a time. A job that
     * is locked or fails stays a candidate; remembering what this run has
     * tried keeps it from being fetched again and again.
     *
     * @return how many jobs {@code action} changed
     */
    private int sweep(Function<Pageable, List<UUID>> candidates, Predicate<UUID> action) {
        Set<UUID> tried = new HashSet<>();
        int changed = 0;
        while (true) {
            List<UUID> chunk = candidates.apply(PageRequest.of(0, CHUNK_SIZE));
            List<UUID> fresh = chunk.stream().filter(tried::add).toList();
            for (UUID id : fresh) {
                try {
                    if (action.test(id)) {
                        changed++;
                    }
                } catch (RuntimeException e) {
                    log.error("Bulk export cleanup of export job {} failed", id, e);
                }
            }
            if (chunk.size() < CHUNK_SIZE || fresh.isEmpty()) {
                return changed;
            }
        }
    }

    boolean warn(UUID exportJobId, OffsetDateTime now) {
        return inLockedJob(exportJobId, job -> {
            OffsetDateTime warnBefore = now.plusHours(properties.getExportExpiryWarningHours());
            if (job.getStatus() != ExportJobStatus.completed || job.getExpiryWarningSentAt() != null
                    || job.getExpiresAt() == null || !job.getExpiresAt().isAfter(now)
                    || job.getExpiresAt().isAfter(warnBefore)) {
                return false;
            }
            job.setExpiryWarningSentAt(now);
            job.setUpdatedAt(now);
            exportJobRepository.save(job);
            outboxEventPublisher.publish(job.getTenantId(), BulkExportServiceImpl.AGGREGATE_TYPE,
                    job.getExportJobId(), BulkExportServiceImpl.EXPIRING_SOON_EVENT_TYPE, expiringSoonPayload(job));
            return true;
        });
    }

    boolean expire(UUID exportJobId, OffsetDateTime now) {
        return inLockedJob(exportJobId, job -> {
            if (job.getStatus() != ExportJobStatus.completed || job.getExpiresAt() == null
                    || job.getExpiresAt().isAfter(now)) {
                return false;
            }
            UUID locationId = job.getFileStorageLocationId();
            String objectKey = job.getFileObjectKey();
            job.setStatus(ExportJobStatus.expired);
            job.setFileObjectKey(null);
            job.setFileStorageLocationId(null);
            job.setUpdatedAt(now);
            exportJobRepository.save(job);
            deleteAfterCommit(locationId, objectKey);
            return true;
        });
    }

    boolean failStuck(UUID exportJobId, OffsetDateTime cutoff) {
        return inLockedJob(exportJobId, job -> {
            OffsetDateTime lastActivity = job.getUpdatedAt() != null ? job.getUpdatedAt() : job.getCreatedAt();
            if (!UNFINISHED_STATUSES.contains(job.getStatus()) || !lastActivity.isBefore(cutoff)) {
                return false;
            }
            log.warn("Failing bulk export {}: {} with no activity since {}", exportJobId, job.getStatus(),
                    lastActivity);
            completion.markFailed(job, REASON_STUCK, 0);
            return true;
        });
    }

    private boolean inLockedJob(UUID exportJobId, Predicate<ExportJob> action) {
        Boolean changed = transactionTemplate.execute(status -> {
            Optional<ExportJob> locked = exportJobRepository.lockForCleanup(exportJobId);
            return locked.isPresent() && action.test(locked.get());
        });
        return Boolean.TRUE.equals(changed);
    }

    private void deleteAfterCommit(UUID locationId, String objectKey) {
        if (locationId == null || objectKey == null
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Optional<StorageLocation> location = storageLocationRepository.findByStorageLocationIdAndDeletedFalse(locationId);
        if (location.isEmpty()) {
            log.warn("Storage location {} of expired export object {} not found; object left in place", locationId,
                    objectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    objectStorageServiceFactory.resolve(location.get()).delete(location.get(), objectKey);
                } catch (RuntimeException e) {
                    log.warn("Failed to delete expired export object {}", objectKey, e);
                }
            }
        });
    }

    /** Payload of {@code export_job.expiring_soon}: who to tell, and when the file goes. */
    static Map<String, Object> expiringSoonPayload(ExportJob job) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportJobId", job.getExportJobId());
        payload.put("tenantId", job.getTenantId());
        payload.put("requestedBy", job.getCreatedBy());
        payload.put("expiresAt", job.getExpiresAt().toString());
        payload.put("correlationId", job.getCorrelationId());
        return payload;
    }
}
