package com.oak.dms.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OBX-04: removes PUBLISHED outbox rows once they are older than the retention period, a few at a time.
 * <p>
 * <b>Off unless enabled</b> ({@code dms.outbox.cleanup.enabled}); the first run is one interval after startup, never at
 * startup. See {@link OutboxCleanupPolicy} for the settings and their provisional defaults and {@link OutboxCleanupStore} for
 * the exact SQL and what it can never delete (pending, retry-scheduled and failed events are untouched; failed events stay
 * until audited replay exists and a retention decision is made for them).
 * <p>
 * <b>Why it is safe to share the scheduler.</b> The application's {@code @Scheduled} jobs share a two-thread pool, so a run is
 * strictly bounded: at most {@code max-batches-per-run} batches (a counted loop, never "until empty"), each in its own
 * transaction of at most {@value #BATCH_TRANSACTION_TIMEOUT_SECONDS} seconds, and no new batch is started once a run has taken
 * {@value #MAX_RUN_MILLIS} ms. A run never overlaps another on the same instance; across instances the {@code SKIP LOCKED}
 * batches simply take different rows. A database failure ends the run (logged without any event data) and the next interval
 * tries again; it never kills the scheduler thread. Nothing here needs a dedicated executor.
 */
@Slf4j
@Component
public class OutboxCleanup {

    static final int BATCH_TRANSACTION_TIMEOUT_SECONDS = 30;
    static final long MAX_RUN_MILLIS = 30_000;

    private final OutboxCleanupStore store;
    private final TransactionOperations transactions;
    private final OutboxCleanupPolicy policy;
    private final long maxRunMillis;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public OutboxCleanup(OutboxCleanupStore store, PlatformTransactionManager transactionManager, OutboxCleanupPolicy policy) {
        this(store, batchTransactions(transactionManager), policy, MAX_RUN_MILLIS);
    }

    /** For tests: the transaction machinery and the run's time budget are injected. */
    OutboxCleanup(OutboxCleanupStore store, TransactionOperations transactions, OutboxCleanupPolicy policy, long maxRunMillis) {
        this.store = store;
        this.transactions = transactions;
        this.policy = policy;
        this.maxRunMillis = maxRunMillis;
    }

    private static TransactionTemplate batchTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);   // every batch commits on its own
        template.setTimeout(BATCH_TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    /** The first run is one interval after startup (never at startup), then one interval after each run ENDS. */
    @Scheduled(fixedDelayString = "${dms.outbox.cleanup.interval-ms:3600000}",
            initialDelayString = "${dms.outbox.cleanup.interval-ms:3600000}")
    public void scheduledRun() {
        runOnce();
    }

    /** One bounded run; returns the number of rows deleted. Never throws for a database failure. */
    public int runOnce() {
        if (!policy.enabled()) {
            return 0;                                              // off: no database access, no log line
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Outbox cleanup is already running on this instance; skipping this tick");
            return 0;
        }
        long started = System.nanoTime();
        int deletedTotal = 0;
        int batches = 0;
        boolean quotaReached = false;
        try {
            for (int batch = 0; batch < policy.maxBatchesPerRun(); batch++) {
                if (batch > 0 && elapsedMs(started) >= maxRunMillis) {
                    quotaReached = true;                           // out of time: leave the rest for the next run
                    break;
                }
                Integer deleted = transactions.execute(status ->
                        store.deletePublishedBatch(policy.publishedRetentionSeconds(), policy.batchSize()));
                int count = deleted == null ? 0 : deleted;
                deletedTotal += count;
                batches++;
                if (count < policy.batchSize()) {
                    break;                                         // fewer than a full batch: nothing eligible is left
                }
                if (batch == policy.maxBatchesPerRun() - 1) {
                    quotaReached = true;                           // a full batch and the quota is used up: more may remain
                }
            }
            log.info("Outbox cleanup finished: deleted={} batches={} batchLimitReached={} durationMs={} retentionHours={} batchSize={}",
                    deletedTotal, batches, quotaReached, elapsedMs(started), policy.publishedRetention().toHours(), policy.batchSize());
        } catch (RuntimeException e) {
            // the batch that failed rolled back; batches already committed stay deleted. Only the exception's own message is
            // logged - never an event's payload, token or e-mail address.
            log.warn("Outbox cleanup FAILED after deleting {} rows in {} batches ({} ms): {}",
                    deletedTotal, batches, elapsedMs(started), e.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
        return deletedTotal;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
