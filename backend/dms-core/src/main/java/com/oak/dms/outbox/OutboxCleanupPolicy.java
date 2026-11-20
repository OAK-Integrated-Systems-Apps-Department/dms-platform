package com.oak.dms.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OBX-04: the validated settings of the outbox cleanup. Everything is checked when the application starts - also while the
 * cleanup is switched off - so a bad value is found at once, not on the day someone enables it.
 * <p>
 * <b>Disabled by default.</b> Nothing is deleted unless {@code dms.outbox.cleanup.enabled=true}.
 * <p>
 * <b>Development defaults</b> (provisional; approved for development testing only, NOT validated against production volume):
 * <ul>
 *   <li>{@code dms.outbox.cleanup.enabled} = {@code false}</li>
 *   <li>{@code dms.outbox.cleanup.published-retention-hours} = 720 (30 days). Must be at least 24: a published event is
 *       never deleted within a day of being published, whatever is configured.</li>
 *   <li>{@code dms.outbox.cleanup.batch-size} = 100 (1 to {@value #MAX_BATCH_SIZE}). One transaction deletes at most this
 *       many rows.</li>
 *   <li>{@code dms.outbox.cleanup.interval-ms} = 3,600,000 (60 minutes; at least {@value #MIN_INTERVAL_MS} ms). The delay
 *       between the END of one run and the start of the next, and also before the first run, so nothing runs at startup.</li>
 *   <li>{@code dms.outbox.cleanup.max-batches-per-run} = 1 (1 to {@value #MAX_BATCHES_PER_RUN}).</li>
 * </ul>
 * With the defaults a run deletes at most 100 rows an hour - 2,400 a day. That is a CAP, not a target: if events are published
 * faster than that, the backlog still grows, and the settings must be raised to match the volume.
 */
@Component
public class OutboxCleanupPolicy {

    public static final long MIN_RETENTION_HOURS = 24;
    public static final long MAX_RETENTION_HOURS = 24L * 365 * 10;
    public static final int MAX_BATCH_SIZE = 10_000;
    public static final long MIN_INTERVAL_MS = 1_000;
    public static final int MAX_BATCHES_PER_RUN = 100;

    public static final boolean DEFAULT_ENABLED = false;
    public static final long DEFAULT_RETENTION_HOURS = 24L * 30;
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final long DEFAULT_INTERVAL_MS = 60L * 60_000;
    public static final int DEFAULT_MAX_BATCHES_PER_RUN = 1;

    private final boolean enabled;
    private final long publishedRetentionHours;
    private final int batchSize;
    private final long intervalMs;
    private final int maxBatchesPerRun;

    @Autowired
    public OutboxCleanupPolicy(
            @Value("${dms.outbox.cleanup.enabled:" + DEFAULT_ENABLED + "}") boolean enabled,
            @Value("${dms.outbox.cleanup.published-retention-hours:" + DEFAULT_RETENTION_HOURS + "}") long publishedRetentionHours,
            @Value("${dms.outbox.cleanup.batch-size:" + DEFAULT_BATCH_SIZE + "}") int batchSize,
            @Value("${dms.outbox.cleanup.interval-ms:" + DEFAULT_INTERVAL_MS + "}") long intervalMs,
            @Value("${dms.outbox.cleanup.max-batches-per-run:" + DEFAULT_MAX_BATCHES_PER_RUN + "}") int maxBatchesPerRun) {
        if (publishedRetentionHours < MIN_RETENTION_HOURS || publishedRetentionHours > MAX_RETENTION_HOURS) {
            throw new IllegalArgumentException("dms.outbox.cleanup.published-retention-hours must be between "
                    + MIN_RETENTION_HOURS + " (24 hours) and " + MAX_RETENTION_HOURS + ": got " + publishedRetentionHours);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("dms.outbox.cleanup.batch-size must be between 1 and " + MAX_BATCH_SIZE
                    + ": got " + batchSize);
        }
        if (intervalMs < MIN_INTERVAL_MS) {
            throw new IllegalArgumentException("dms.outbox.cleanup.interval-ms must be at least " + MIN_INTERVAL_MS
                    + ": got " + intervalMs);
        }
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalArgumentException("dms.outbox.cleanup.max-batches-per-run must be between 1 and "
                    + MAX_BATCHES_PER_RUN + ": got " + maxBatchesPerRun);
        }
        this.enabled = enabled;
        this.publishedRetentionHours = publishedRetentionHours;
        this.batchSize = batchSize;
        this.intervalMs = intervalMs;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    public static OutboxCleanupPolicy defaults() {
        return new OutboxCleanupPolicy(DEFAULT_ENABLED, DEFAULT_RETENTION_HOURS, DEFAULT_BATCH_SIZE, DEFAULT_INTERVAL_MS,
                DEFAULT_MAX_BATCHES_PER_RUN);
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration publishedRetention() {
        return Duration.ofHours(publishedRetentionHours);
    }

    /** The retention in seconds, as handed to PostgreSQL, which subtracts it from ITS clock. */
    public long publishedRetentionSeconds() {
        return publishedRetention().toSeconds();
    }

    public int batchSize() {
        return batchSize;
    }

    public long intervalMs() {
        return intervalMs;
    }

    public int maxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    /** The most rows one run can delete: {@code batchSize * maxBatchesPerRun}. */
    public long maxRowsPerRun() {
        return (long) batchSize * maxBatchesPerRun;
    }
}
