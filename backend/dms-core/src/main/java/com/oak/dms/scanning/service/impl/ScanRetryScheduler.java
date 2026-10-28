package com.oak.dms.scanning.service.impl;

import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.repository.ScanResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Where pending scan work is tracked and finished - the fail-closed half of
 * the scan gate. On every tick it:
 * <ol>
 *   <li>wakes {@link ScanWorkPool}, whose workers claim due rows one at a time
 *   ({@code pending}/{@code infected}, {@code next_attempt_at <= now()}, no
 *   live lease - {@code FOR UPDATE SKIP LOCKED}, so several workers or
 *   instances never share a row) and hand each to {@link IngestionProcessor}:
 *   retries after an engine outage, polls pending cloud verdicts, isolates
 *   infected files whose quarantine copy failed, and recovers rows whose
 *   worker died (its lease simply expired) - including failing uploads
 *   interrupted mid-staging;</li>
 *   <li>purges staged objects of finished rows ({@code failed} ones only after
 *   {@code failed-retention-hours}).</li>
 * </ol>
 * The work itself runs on the pool's own threads, not this scheduler thread -
 * see {@link ScanWorkPool} for why.
 */
@Slf4j
@Component
public class ScanRetryScheduler {

    private final ScanResultRepository scanResultRepository;
    private final IngestionProcessor ingestionProcessor;
    private final ScanWorkPool scanWorkPool;
    private final ScanProperties scanProperties;

    public ScanRetryScheduler(ScanResultRepository scanResultRepository,
                              IngestionProcessor ingestionProcessor,
                              ScanWorkPool scanWorkPool,
                              ScanProperties scanProperties) {
        this.scanResultRepository = scanResultRepository;
        this.ingestionProcessor = ingestionProcessor;
        this.scanWorkPool = scanWorkPool;
        this.scanProperties = scanProperties;
    }

    @Scheduled(fixedDelayString = "${dms.scanning.retry.poll-interval-ms:30000}",
            initialDelayString = "${dms.scanning.retry.poll-interval-ms:30000}")
    public void runDueWork() {
        scanWorkPool.wake();

        List<UUID> toPurge = scanResultRepository.findStagingToPurge(
                Duration.ofHours(scanProperties.getFailedRetentionHours()), scanProperties.getRetry().getBatchSize());
        for (UUID scanResultId : toPurge) {
            try {
                ingestionProcessor.purgeStaging(scanResultId);
            } catch (RuntimeException e) {
                log.warn("Staging purge for {} failed: {}", scanResultId, e.getMessage());
            }
        }
    }
}
