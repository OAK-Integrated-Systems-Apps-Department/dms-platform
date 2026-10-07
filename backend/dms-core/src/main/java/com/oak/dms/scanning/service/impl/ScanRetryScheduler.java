package com.oak.dms.scanning.service.impl;

import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.repository.ScanResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Where pending scan work is tracked and finished - the fail-closed half of
 * the scan gate. On every tick it:
 * <ol>
 *   <li>claims due rows one at a time ({@code pending}/{@code infected},
 *   {@code next_attempt_at <= now()}, no live lease - {@code FOR UPDATE SKIP
 *   LOCKED}, so several instances never share a row) and hands each to
 *   {@link IngestionProcessor}: retries after an engine outage, polls
 *   pending cloud verdicts, isolates infected files whose quarantine copy
 *   failed, and recovers rows whose worker died (its lease simply expired) -
 *   including failing uploads interrupted mid-staging;</li>
 *   <li>purges staged objects of finished rows ({@code failed} ones only after
 *   {@code failed-retention-hours}).</li>
 * </ol>
 * Rows are claimed singly rather than in a batch so a slow scan never lets
 * the leases of rows still queued behind it expire.
 */
@Slf4j
@Component
public class ScanRetryScheduler {

    private final ScanResultRepository scanResultRepository;
    private final IngestionProcessor ingestionProcessor;
    private final ScanProperties scanProperties;
    private final TransactionTemplate transactionTemplate;

    public ScanRetryScheduler(ScanResultRepository scanResultRepository,
                              IngestionProcessor ingestionProcessor,
                              ScanProperties scanProperties,
                              PlatformTransactionManager transactionManager) {
        this.scanResultRepository = scanResultRepository;
        this.ingestionProcessor = ingestionProcessor;
        this.scanProperties = scanProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${dms.scanning.retry.poll-interval-ms:30000}",
            initialDelayString = "${dms.scanning.retry.poll-interval-ms:30000}")
    public void runDueWork() {
        ScanProperties.Retry retry = scanProperties.getRetry();
        Duration lease = Duration.ofSeconds(retry.getLeaseSeconds());
        Duration leasePerGb = Duration.ofSeconds(retry.getLeaseSecondsPerGb());

        for (int i = 0; i < retry.getBatchSize(); i++) {
            List<UUID> claimed = transactionTemplate.execute(status -> scanResultRepository.claimDueWork(1, lease, leasePerGb));
            if (claimed == null || claimed.isEmpty()) {
                break;
            }
            UUID scanResultId = claimed.get(0);
            try {
                ingestionProcessor.process(scanResultId);
            } catch (RuntimeException e) {
                // The row's lease expires and it is picked up again - never promoted on error.
                log.error("Scan retry for {} failed", scanResultId, e);
            }
        }

        List<UUID> toPurge = scanResultRepository.findStagingToPurge(
                Duration.ofHours(scanProperties.getFailedRetentionHours()), retry.getBatchSize());
        for (UUID scanResultId : toPurge) {
            try {
                ingestionProcessor.purgeStaging(scanResultId);
            } catch (RuntimeException e) {
                log.warn("Staging purge for {} failed: {}", scanResultId, e.getMessage());
            }
        }
    }
}
