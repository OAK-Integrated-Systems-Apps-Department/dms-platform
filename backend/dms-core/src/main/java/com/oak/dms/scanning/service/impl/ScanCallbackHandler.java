package com.oak.dms.scanning.service.impl;

import com.oak.dms.scanning.dto.ScanCallbackEvent;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.ScanEngineResolver;
import com.oak.dms.storage.entity.StorageLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acts on an (already authenticated) cloud verdict notification. <b>A
 * callback never sets a verdict</b>: it only finds the scan result the
 * notification is about, records its threat-name hint, makes the row due
 * now and - if nobody else holds it - processes it straight away, which
 * re-reads the verdict from the provider's own object tag. A forged or
 * replayed callback can therefore at most cause an early re-check.
 * <p>
 * The row is found from the staged object key ({@code staging/<tenantId>/<scanResultId>})
 * and must match on key, container/bucket, engine provider and - when both
 * sides have one - the ETag recorded at staging, or the event is ignored.
 */
@Slf4j
@Component
public class ScanCallbackHandler {

    private static final Pattern STAGING_KEY = Pattern.compile(
            "^staging/([0-9a-fA-F-]{36})/([0-9a-fA-F-]{36})$");
    private static final int MAX_THREAT_NAME_LENGTH = 500;

    private final ScanResultRepository scanResultRepository;
    private final ScanEngineResolver scanEngineResolver;
    private final IngestionProcessor ingestionProcessor;
    private final ScanProperties scanProperties;
    private final TransactionTemplate transactionTemplate;

    public ScanCallbackHandler(ScanResultRepository scanResultRepository,
                               ScanEngineResolver scanEngineResolver,
                               IngestionProcessor ingestionProcessor,
                               ScanProperties scanProperties,
                               PlatformTransactionManager transactionManager) {
        this.scanResultRepository = scanResultRepository;
        this.scanEngineResolver = scanEngineResolver;
        this.ingestionProcessor = ingestionProcessor;
        this.scanProperties = scanProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** @return true if the event matched a pending scan result */
    public boolean handle(ScanProvider provider, ScanCallbackEvent event) {
        UUID scanResultId = scanResultIdFrom(event.objectKey());
        if (scanResultId == null) {
            log.info("{} callback for a non-staging object ignored", provider);
            return false;
        }
        ScanResult row = scanResultRepository.findById(scanResultId).orElse(null);
        if (row == null || row.isDeleted() || !matches(provider, row, event)) {
            log.warn("{} callback for {} does not match a scan result - ignored", provider, event.objectKey());
            return false;
        }

        String threatHint = event.threatNames() == null || event.threatNames().isEmpty()
                ? null
                : truncate(String.join(", ", event.threatNames()));
        Boolean due = transactionTemplate.execute(status -> scanResultRepository.lockById(scanResultId)
                .filter(locked -> locked.getStatus() == ScanResultStatus.pending)
                .map(locked -> {
                    locked.setNextAttemptAt(OffsetDateTime.now());
                    if (threatHint != null) {
                        locked.setThreatName(threatHint);
                    }
                    scanResultRepository.save(locked);
                    return true;
                })
                .orElse(false));
        if (!Boolean.TRUE.equals(due)) {
            return false;
        }
        log.info("{} verdict notification for scan result {} (provider id {})", provider, scanResultId,
                event.providerScanId());

        Duration lease = Duration.ofSeconds(scanProperties.getRetry().getLeaseSeconds());
        Duration leasePerGb = Duration.ofSeconds(scanProperties.getRetry().getLeaseSecondsPerGb());
        if (Boolean.TRUE.equals(transactionTemplate.execute(status ->
                scanResultRepository.claimOne(scanResultId, lease, leasePerGb)))) {
            ingestionProcessor.process(scanResultId);
        }
        // Otherwise another worker holds it; the scheduler picks it up when due.
        return true;
    }

    private boolean matches(ScanProvider provider, ScanResult row, ScanCallbackEvent event) {
        if (!event.objectKey().equals(row.getStagingObjectKey())) {
            return false;
        }
        ScanEngine engine = scanEngineResolver.resolveById(row.getScanEngineId());
        if (engine.getProvider() != provider) {
            return false;
        }
        StorageLocation staging = ingestionProcessor.loadLocation(row.getStagingLocationId());
        if (staging.getBucket() != null && !staging.getBucket().isBlank()
                && !staging.getBucket().equals(event.container())) {
            return false;
        }
        return IngestionProcessor.sameETag(row.getExternalScanId(), event.eTag());
    }

    static UUID scanResultIdFrom(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        Matcher matcher = STAGING_KEY.matcher(objectKey);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(2));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_THREAT_NAME_LENGTH ? value : value.substring(0, MAX_THREAT_NAME_LENGTH);
    }

    /** Convenience for the controller. */
    public int handleAll(ScanProvider provider, List<ScanCallbackEvent> events) {
        int matched = 0;
        for (ScanCallbackEvent event : events) {
            try {
                if (handle(provider, event)) {
                    matched++;
                }
            } catch (RuntimeException e) {
                log.error("{} callback event for {} failed", provider, event.objectKey(), e);
            }
        }
        return matched;
    }
}
