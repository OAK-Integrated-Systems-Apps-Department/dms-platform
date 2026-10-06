package com.oak.dms.watermark.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Queues a watermark job, but only once its row is actually committed.
 *
 * <h2>Why after commit and not at save time</h2>
 * The worker's first act is to load the job and claim it under a pessimistic
 * lock. Handing it the id while the inserting transaction is still open
 * gives two outcomes, both bad: the worker finds no row and the job is
 * silently never produced, or it blocks on the lock holding one of the two
 * job threads until the request commits. Queueing after commit means the row
 * is always there by the time anyone looks for it.
 * <p>
 * Same approach as {@code BulkExportService.request}, deliberately: a
 * reviewer who knows how exports are queued should recognise this
 * immediately.
 * <p>
 * The {@code isSynchronizationActive} branch is not defensive padding - it
 * is what makes this work when called outside a transaction, which is how
 * {@code resumeInterrupted} re-queues jobs at startup.
 *
 * <h2>Why the worker is injected lazily</h2>
 * {@code WatermarkService} needs this, and the worker needs the services
 * that read documents - a cycle Spring would refuse to construct. The
 * {@link ObjectProvider} breaks it without annotating anything
 * {@code @Lazy}, which would hide the cycle rather than remove it.
 */
@Slf4j
@Component
public class WatermarkJobStarter {

    private final ObjectProvider<WatermarkJobQueue> worker;

    public WatermarkJobStarter(ObjectProvider<WatermarkJobQueue> worker) {
        this.worker = worker;
    }

    public void startAfterCommit(UUID jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start(jobId);
                }
            });
        } else {
            start(jobId);
        }
    }

    private void start(UUID jobId) {
        WatermarkJobQueue available = worker.getIfAvailable();
        if (available == null) {
            // The feature is switched off, so the worker bean doesn't exist.
            // The row stays pending and is picked up if it is ever switched
            // on - which is better than failing a job that was accepted.
            log.warn("Watermark job {} was created but no worker is available - leaving it pending", jobId);
            return;
        }
        available.start(jobId);
    }
}
