package com.oak.dms.scanning.service.impl;

import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.security.auth.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs due scan work - large files the upload request handed off, retries,
 * pending cloud verdicts, expired leases - on its own threads
 * ({@code dms.scanning.retry.workers}, "scan-worker-N"), so several files are
 * scanned and promoted at once.
 * <p>
 * This used to run on Spring's single scheduler thread inside
 * {@link ScanRetryScheduler}: one 6 GB ZIP (scan, then copy + encrypt into the
 * document location) held that thread for 10-20 minutes, so a second upload
 * waited behind it - and so did every other {@code @Scheduled} job in the app.
 * <p>
 * The queue is still the {@code scan_results} table: each worker claims one
 * due row at a time ({@code FOR UPDATE SKIP LOCKED}, with a lease), so workers
 * here and on other instances never share a row, and a row whose worker dies
 * is picked up again once its lease runs out. A worker that claims a row first
 * wakes another one to look for the next, then works on its own, until no due
 * row is left.
 * <p>
 * {@link #wake()} is called by the upload request right after it hands a file
 * off, so it starts at once rather than at the next scheduler tick, and by
 * {@link ScanRetryScheduler} on every tick as the safety net (a wake that
 * arrives while every worker is about to stop is picked up by that tick).
 */
@Slf4j
@Component
public class ScanWorkPool implements DisposableBean {

    private final ScanResultRepository scanResultRepository;
    private final IngestionProcessor ingestionProcessor;
    private final ScanProperties scanProperties;
    private final TransactionTemplate transactionTemplate;
    private final int workers;
    private final ExecutorService executor;
    private final AtomicInteger running = new AtomicInteger();
    private volatile boolean stopping;

    public ScanWorkPool(ScanResultRepository scanResultRepository,
                        IngestionProcessor ingestionProcessor,
                        ScanProperties scanProperties,
                        PlatformTransactionManager transactionManager) {
        this.scanResultRepository = scanResultRepository;
        this.ingestionProcessor = ingestionProcessor;
        this.scanProperties = scanProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.workers = Math.max(1, scanProperties.getRetry().getWorkers());
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "scan-worker-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts one more worker if there's room - it stops again by itself when nothing is due. */
    public void wake() {
        while (!stopping) {
            int now = running.get();
            if (now >= workers) {
                return;
            }
            if (running.compareAndSet(now, now + 1)) {
                try {
                    executor.execute(this::drain);
                } catch (RejectedExecutionException e) {
                    running.decrementAndGet();
                }
                return;
            }
        }
    }

    private void drain() {
        try {
            ScanProperties.Retry retry = scanProperties.getRetry();
            Duration lease = Duration.ofSeconds(retry.getLeaseSeconds());
            Duration leasePerGb = Duration.ofSeconds(retry.getLeaseSecondsPerGb());
            while (!stopping) {
                List<UUID> claimed = transactionTemplate.execute(
                        status -> scanResultRepository.claimDueWork(1, lease, leasePerGb));
                if (claimed == null || claimed.isEmpty()) {
                    return;
                }
                wake(); // there may be more due - let another worker look while this one works
                UUID scanResultId = claimed.get(0);
                try {
                    // The row's tenant, so a file promoted here is encrypted under that tenant's own
                    // master key (V57) - without it EncryptingObjectStorageService fell back to the
                    // platform key (fixed 2026-10-07, see V73). Tenant only, no user: the work is the
                    // system's, so its audit events keep the SYSTEM actor.
                    TenantContext.set(scanResultRepository.findById(scanResultId)
                            .map(ScanResult::getTenantId).orElse(null), null);
                    ingestionProcessor.process(scanResultId);
                } catch (RuntimeException e) {
                    // The row's lease expires and it is picked up again - never promoted on error.
                    log.error("Scan work for {} failed", scanResultId, e);
                } finally {
                    TenantContext.clear();
                }
            }
        } catch (RuntimeException e) {
            log.error("Scan worker stopped early - the next scheduler tick starts another", e);
        } finally {
            running.decrementAndGet();
        }
    }

    /** Interrupts work in progress: its row's lease runs out and the next start picks it up again. */
    @Override
    public void destroy() {
        stopping = true;
        executor.shutdownNow();
    }
}
