package com.oak.dms.watermark.job;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.exception.WatermarkQueueFullException;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Queues a watermark job, but only once its row is actually committed - and
 * cleans up after itself if the queue turns it away.
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
 * immediately. The {@code isSynchronizationActive} branch is not defensive
 * padding - it is what makes this work when called outside a transaction,
 * which is how {@code resumeInterrupted} re-queues jobs at startup.
 *
 * <h2>What happens when the queue is full</h2>
 * Queueing after commit creates a window the request side cannot roll back:
 * the row is already durable when the executor refuses it. Left alone, that
 * row sits in {@code pending} for ever - never produced, because nothing
 * will ever pick it up, and counting against the requester's active-job cap,
 * so after two of them they can't ask for anything at all. The failure would
 * be permanent and invisible.
 * <p>
 * So a rejection is turned into a FAILED job in its own transaction, and
 * then a 503. Failed is not one of the statuses the active-job cap counts,
 * and it releases the idempotency key, so the person can simply try again -
 * which is exactly what {@code Retry-After} is telling them to do.
 * <p>
 * Checking the queue's depth before inserting instead would narrow the
 * window but not close it: between the check and the submit, two other
 * requests can fill it. This handles the real case rather than the likely
 * one.
 */
@Slf4j
@Component
public class WatermarkJobStarter {

    private final ObjectProvider<WatermarkJobQueue> worker;
    private final WatermarkJobRepository jobRepository;
    private final WatermarkProperties properties;
    private final TransactionTemplate newTransaction;

    public WatermarkJobStarter(ObjectProvider<WatermarkJobQueue> worker,
                               WatermarkJobRepository jobRepository,
                               WatermarkProperties properties,
                               PlatformTransactionManager transactionManager) {
        this.worker = worker;
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.newTransaction = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: this runs from afterCommit, where the request's own
        // transaction is finished. Marking the job failed needs a
        // transaction of its own or it would not be written at all.
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        try {
            available.start(jobId);
        } catch (RuntimeException e) {
            // Includes TaskRejectedException from a full bounded queue.
            // Caught as RuntimeException rather than that one type so a
            // different executor, or a rejection wrapped by something else,
            // can't leave a job stranded.
            log.warn("Watermark job {} could not be queued - failing it so it doesn't linger", jobId, e);
            markQueueFull(jobId);
            throw new WatermarkQueueFullException(properties.getExecutor().getRetryAfter());
        }
    }

    /**
     * Never lets its own failure escape: the caller is already about to
     * throw a 503, and turning a bookkeeping problem into a 500 would be a
     * worse answer to "we're busy". A row that somehow survives as pending
     * is picked up by the worker's own startup resume.
     */
    private void markQueueFull(UUID jobId) {
        try {
            newTransaction.executeWithoutResult(status -> jobRepository.lockById(jobId).ifPresent(job -> {
                if (job.getStatus() != WatermarkJobStatus.pending) {
                    return;   // something already moved it on
                }
                OffsetDateTime now = OffsetDateTime.now();
                job.setStatus(WatermarkJobStatus.failed);
                job.setFailureCode("WATERMARK_QUEUE_FULL");
                job.setFailureReason("The server was too busy to start this watermark. Please try again.");
                job.setCompletedAt(now);
                job.setUpdatedAt(now);
                jobRepository.save(job);
            }));
        } catch (RuntimeException e) {
            log.error("Could not mark rejected watermark job {} as failed", jobId, e);
        }
    }
}
