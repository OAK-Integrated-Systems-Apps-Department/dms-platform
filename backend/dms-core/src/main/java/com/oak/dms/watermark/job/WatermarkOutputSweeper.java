package com.oak.dms.watermark.job;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Deletes watermarked copies once they expire, and any left behind by a
 * document that has since been deleted.
 *
 * <h2>Why this matters more than an export's purge</h2>
 * A watermarked copy is a second place a document's content exists, and it
 * carries the name of the person who took it. Keeping it past its
 * {@code expires_at} is a quiet extension of how long that content is
 * reachable, and of how long a leaked copy stays downloadable.
 *
 * <h2>Why it stays small</h2>
 * {@code @Scheduled} work shares one thread with {@code OutboxRelay} (every
 * two seconds), {@code RecycleBinService} and {@code KeyRotationScheduler}.
 * A long sweep here delays the outbox relay, so this takes a capped BATCH
 * per run rather than everything that qualifies, and leaves the rest for the
 * next tick. The scheduler pool is deliberately left alone -
 * {@code spring.task.scheduling.pool.size} is not touched by this feature.
 * <p>
 * A Postgres advisory lock keeps two instances from sweeping the same rows,
 * exactly as {@code RecycleBinService.purgeExpired} does - including taking
 * and releasing it on one pinned connection, which is not optional: see
 * {@link #sweep()}. Without the lock, both instances would try to delete the
 * same objects and log the same noise.
 *
 * <h2>Shape borrowed from BulkExportService.purgeExpired</h2>
 * File first, then the row - so a sweep interrupted half way simply runs
 * again, and deleting an object that is already gone is not an error. The
 * job row itself is KEPT, as the record of what was produced; only the file
 * and the pointer to it go.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dms.watermark", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class WatermarkOutputSweeper {

    /** Arbitrary, fixed, and distinct from the recycle bin's own key. */
    private static final long ADVISORY_LOCK_KEY = 0x774D_0001L;
    /** Capped so one tick cannot hold the shared scheduler thread for long. */
    private static final int BATCH = 200;

    private final WatermarkJobRepository repository;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory storage;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    @Scheduled(fixedDelayString = "${dms.watermark.sweep-interval:PT15M}", initialDelayString = "PT2M")
    public void sweep() {
        // Lock and unlock MUST run on the same connection. A Postgres
        // advisory lock is held by the SESSION, and two separate
        // JdbcTemplate calls can be handed two different pooled
        // connections - which unlocks nothing, leaks the lock for the life
        // of that connection, and leaves every later sweep returning here
        // having done nothing, silently and indefinitely. ConnectionCallback
        // pins both to one connection, exactly as RecycleBinService and
        // KeyRotationScheduler already do.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!advisory(connection, "select pg_try_advisory_lock(?)")) {
                return null;   // another instance is sweeping
            }
            try {
                int removed = 0;
                removed += purge(repository.findExpiredOutputs(OffsetDateTime.now(), PageRequest.of(0, BATCH)),
                        "expired");
                removed += purge(repository.findOutputsOfDeletedDocuments(PageRequest.of(0, BATCH)),
                        "belonging to a deleted document");
                if (removed > 0) {
                    log.info("Swept {} watermarked copies", removed);
                }
            } catch (RuntimeException e) {
                log.error("Watermark output sweep failed - it will run again", e);
            } finally {
                advisory(connection, "select pg_advisory_unlock(?)");
            }
            return null;
        });
    }

    private boolean advisory(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private int purge(List<WatermarkJob> jobs, String why) {
        int removed = 0;
        for (WatermarkJob job : jobs) {
            try {
                deleteObject(job);
                // Only now is the row updated: if the delete failed we want
                // to come back to it, and re-deleting something already gone
                // costs nothing.
                //
                // The update needs its own transaction, and explicitly: this
                // runs on the scheduler thread with nothing around it, and
                // lockById is PESSIMISTIC_WRITE, which throws "No active
                // transaction" outside one. @Transactional on this class
                // would not help - purge() is called from sweep() on the
                // same bean, so the proxy is bypassed - and the throw is
                // caught just below as a per-job warning, so the sweep would
                // have gone on reporting success while purging nothing.
                transactions.executeWithoutResult(status ->
                        repository.lockById(job.getJobId()).ifPresent(locked -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            if (locked.getStatus() == WatermarkJobStatus.completed) {
                                // The row stays as the record of what was
                                // produced; only the file and the pointer go.
                                locked.setStatus(WatermarkJobStatus.expired);
                            }
                            locked.setOutputObjectKey(null);
                            locked.setPurgedAt(now);
                            locked.setUpdatedAt(now);
                            repository.save(locked);
                        }));
                removed++;
                log.debug("Swept watermark output for job {} ({})", job.getJobId(), why);
            } catch (RuntimeException e) {
                log.warn("Could not sweep watermark output for job {}: {}", job.getJobId(), e.getMessage());
            }
        }
        return removed;
    }

    private void deleteObject(WatermarkJob job) {
        if (job.getOutputObjectKey() == null || job.getStorageLocationId() == null) {
            return;
        }
        storageLocationRepository.findByStorageLocationIdAndDeletedFalse(job.getStorageLocationId())
                .ifPresent(location -> {
                    try {
                        storage.resolve(location).delete(location, job.getOutputObjectKey());
                    } catch (StorageObjectNotFoundException e) {
                        // Already gone - a previous run got this far.
                    }
                });
    }
}
