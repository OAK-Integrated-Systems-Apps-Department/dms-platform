package com.oak.dms.scanning.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.scanning.dto.ScanTarget;
import com.oak.dms.scanning.dto.ThreatQuarantinedEvent;
import com.oak.dms.scanning.dto.ScanVerdict;
import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.QuarantineStatus;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.ScanException;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.helper.StorageObjectMover;
import com.oak.dms.scanning.repository.QuarantineRecordRepository;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.scanning.service.ScanEngineFactory;
import com.oak.dms.scanning.service.ScanEngineResolver;
import com.oak.dms.scanning.service.ScanService;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.exception.StorageException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Takes one leased {@code scan_results} row from "staged" to its outcome:
 * scan it, then promote it (clean), quarantine it (infected), reject it
 * (unscannable), wait (cloud verdict pending) or schedule a retry (engine
 * failure). Used identically by the upload request (ClamAV's synchronous
 * path), {@link ScanRetryScheduler} and cloud callbacks - there is one
 * processing path, not three.
 * <p>
 * <b>Fail closed.</b> Scans and storage copies run outside any database
 * transaction; every state change is its own short transaction that
 * re-locks the row and re-checks that it is still in the expected status
 * and that this worker's claim lease is still the current one (the lease
 * value read when processing started is the ownership token). A worker
 * whose lease was taken over - because it stalled or its process died -
 * therefore can never commit an outcome. A clean file reaches the document
 * location only after a CLEAN verdict, only as the exact bytes that were
 * hashed at staging ({@link StorageObjectMover#copyVerified}), and only
 * becomes reachable through a version row the V39 trigger validates.
 */
@Slf4j
@Component
public class IngestionProcessor {

    static final String UNKNOWN_THREAT = "UNKNOWN (see provider finding)";

    private final ScanResultRepository scanResultRepository;
    private final QuarantineRecordRepository quarantineRecordRepository;
    private final ScanEngineResolver scanEngineResolver;
    private final ScanEngineFactory scanEngineFactory;
    private final StorageLocationRepository storageLocationRepository;
    private final StorageLocationResolver storageLocationResolver;
    private final StorageObjectMover storageObjectMover;
    private final DocumentPromoter documentPromoter;
    private final AuditService auditService;
    private final ScanProperties scanProperties;
    private final ReleaseAttemptFinalizer releaseAttemptFinalizer;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public IngestionProcessor(ScanResultRepository scanResultRepository,
                              QuarantineRecordRepository quarantineRecordRepository,
                              ScanEngineResolver scanEngineResolver,
                              ScanEngineFactory scanEngineFactory,
                              StorageLocationRepository storageLocationRepository,
                              StorageLocationResolver storageLocationResolver,
                              StorageObjectMover storageObjectMover,
                              DocumentPromoter documentPromoter,
                              AuditService auditService,
                              ScanProperties scanProperties,
                              ReleaseAttemptFinalizer releaseAttemptFinalizer,
                              ApplicationEventPublisher eventPublisher,
                              PlatformTransactionManager transactionManager) {
        this.scanResultRepository = scanResultRepository;
        this.quarantineRecordRepository = quarantineRecordRepository;
        this.scanEngineResolver = scanEngineResolver;
        this.scanEngineFactory = scanEngineFactory;
        this.storageLocationRepository = storageLocationRepository;
        this.storageLocationResolver = storageLocationResolver;
        this.storageObjectMover = storageObjectMover;
        this.documentPromoter = documentPromoter;
        this.auditService = auditService;
        this.scanProperties = scanProperties;
        this.releaseAttemptFinalizer = releaseAttemptFinalizer;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Processes a row the caller has just leased. Never throws for an engine
     * or storage failure - those become a scheduled retry (or, once retries
     * are exhausted, {@code failed}). Returns the row's state afterwards.
     */
    public ScanResult process(UUID scanResultId) {
        ScanResult row = scanResultRepository.findById(scanResultId).orElse(null);
        if (row == null || row.isDeleted()) {
            return row;
        }
        OffsetDateTime leaseToken = row.getLeaseExpiresAt();

        try {
            if (row.getStatus() == ScanResultStatus.infected) {
                isolate(row, leaseToken);
                return reload(scanResultId);
            }
            if (row.getStatus() != ScanResultStatus.pending) {
                return row;
            }
            if (row.getFileHash() == null) {
                // Only reachable once the staging request's lease expired: the
                // process died mid-upload, and the client's stream is gone.
                failTerminal(row.getScanResultId(), leaseToken, "Upload was interrupted before staging completed");
                return reload(scanResultId);
            }

            ScanEngine engine = scanEngineResolver.resolveById(row.getScanEngineId());
            StorageLocation staging = loadLocation(row.getStagingLocationId());
            ScanService scanner = scanEngineFactory.resolveCompatible(engine, staging);

            if (scanner.scanMode() == ScanMode.post_store_object && cloudDeadlinePassed(row)) {
                failTerminal(row.getScanResultId(), leaseToken, "No verdict from " + engine.getProvider()
                        + " within " + scanProperties.getCloudVerdictTimeoutMinutes() + " minutes");
                return reload(scanResultId);
            }

            long started = System.nanoTime();
            ScanVerdict verdict = scanner.scan(engine, target(row, staging));
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            switch (verdict.status()) {
                case CLEAN -> promote(row, staging, verdict, durationMs, leaseToken);
                case INFECTED -> {
                    if (row.isReleaseAttempt()) {
                        // A quarantine release re-scan that still detects the
                        // threat: the file already sits under its original
                        // quarantine record - refuse the release, don't
                        // quarantine it a second time.
                        refuseRelease(row.getScanResultId(), leaseToken, verdict, durationMs);
                    } else if (markInfected(row.getScanResultId(), leaseToken, verdict, durationMs)) {
                        isolate(reload(scanResultId), leaseToken);
                    }
                }
                case UNSCANNABLE -> markUnscannable(row.getScanResultId(), leaseToken,
                        verdict.threatName() != null ? verdict.threatName() : "Encrypted or password-protected content",
                        verdict);
                case PENDING -> reschedule(row.getScanResultId(), leaseToken,
                        OffsetDateTime.now().plus(Duration.ofMillis(scanProperties.getRetry().getPollIntervalMs())));
            }
        } catch (ScanException | StorageObjectMover.StagedObjectChangedException | StorageException e) {
            recordFailure(scanResultId, leaseToken, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected failure processing scan result {}", scanResultId, e);
            recordFailure(scanResultId, leaseToken, "Unexpected error: " + e.getClass().getSimpleName());
        }
        return reload(scanResultId);
    }

    /** Terminal status and a staged object still present: delete it and record that. Idempotent. */
    public void purgeStaging(UUID scanResultId) {
        ScanResult row = scanResultRepository.findById(scanResultId).orElse(null);
        if (row == null || row.getStagingObjectKey() == null || row.getStagingPurgedAt() != null
                || !row.getStatus().isTerminal()) {
            return;
        }
        StorageLocation staging = loadLocation(row.getStagingLocationId());
        if (storageObjectMover.deleteQuietly(staging, row.getStagingObjectKey())) {
            transactionTemplate.executeWithoutResult(status -> scanResultRepository.lockById(scanResultId)
                    .ifPresent(locked -> {
                        locked.setStagingPurgedAt(OffsetDateTime.now());
                        scanResultRepository.save(locked);
                    }));
        }
    }

    // ------------------------------------------------------------------
    // Outcomes
    // ------------------------------------------------------------------

    private void promote(ScanResult row, StorageLocation staging, ScanVerdict verdict, long durationMs,
                         OffsetDateTime leaseToken) {
        DocumentPromoter.PromotionPlan plan = documentPromoter.prepare(row);
        storageObjectMover.copyVerified(staging, row.getStagingObjectKey(), plan.documentLocation(),
                plan.objectKey(), row.getSizeBytes(), row.getMimeType(), row.getFileHash());

        ReleaseAttemptFinalizer.ReleasedObject[] released = new ReleaseAttemptFinalizer.ReleasedObject[1];
        boolean committed;
        try {
            committed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                ScanResult locked = lockOwned(row.getScanResultId(), leaseToken, ScanResultStatus.pending);
                if (locked == null) {
                    return false;
                }
                OffsetDateTime now = OffsetDateTime.now();
                applyEngineInfo(locked, verdict, durationMs, now);
                locked.setStatus(ScanResultStatus.clean);
                locked.setThreatName(null);
                locked.setFailureReason(null);
                locked.setDocumentVersionId(plan.versionId());
                locked.setLeaseExpiresAt(null);
                locked.setNextAttemptAt(null);
                locked.setUpdatedAt(now);
                documentPromoter.persist(plan, locked);
                scanResultRepository.save(locked);

                audit(AuditActionCatalog.DOCUMENT_SCAN_CLEAN, locked, AuditOutcome.SUCCESS, null, scanValues(locked,
                        Map.of("documentId", plan.documentId(), "versionId", plan.versionId())));
                auditService.event(AuditActionCatalog.DOCUMENT_UPLOADED)
                        .tenantId(locked.getTenantId())
                        .objectType(AuditObjectTypes.DOCUMENT)
                        .objectId(plan.documentId())
                        .resourceName(locked.getTitle() != null ? locked.getTitle() : locked.getOriginalFilename())
                        .outcome(AuditOutcome.SUCCESS)
                        .newValue(Map.of("versionId", plan.versionId(), "sizeBytes", locked.getSizeBytes(),
                                "scanResultId", locked.getScanResultId(), "uploadedBy", locked.getUploadedBy()))
                        .log();
                if (locked.isReleaseAttempt()) {
                    // A quarantine release re-scan came back clean: close out the
                    // quarantine record in this same transaction (V40).
                    released[0] = releaseAttemptFinalizer.markReleased(locked, plan.documentId());
                }
                return true;
            }));
        } catch (RuntimeException e) {
            // Commit refused (e.g. the V39 gate trigger) - the copied object is
            // unreferenced; remove it and treat this as a failed attempt.
            storageObjectMover.deleteQuietly(plan.documentLocation(), plan.objectKey());
            throw e;
        }

        if (!committed) {
            // Lost the lease to another worker: the copy we made is unreferenced.
            storageObjectMover.deleteQuietly(plan.documentLocation(), plan.objectKey());
            return;
        }
        log.info("Scan result {} clean - promoted to document {} version {}", row.getScanResultId(),
                plan.documentId(), plan.versionId());
        purgeStaging(row.getScanResultId());
        if (released[0] != null) {
            // The released file now lives in the document location; its
            // quarantine copy is no longer needed (the record keeps the hash).
            storageLocationRepository.findByStorageLocationIdAndDeletedFalse(released[0].quarantineLocationId())
                    .ifPresent(location -> storageObjectMover.deleteQuietly(location, released[0].quarantineObjectKey()));
        }
    }

    private boolean markInfected(UUID scanResultId, OffsetDateTime leaseToken, ScanVerdict verdict, long durationMs) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            ScanResult locked = lockOwned(scanResultId, leaseToken, ScanResultStatus.pending);
            if (locked == null) {
                return false;
            }
            OffsetDateTime now = OffsetDateTime.now();
            // A callback's threat-name hint (stored on the row) is only used now
            // that the provider's own verdict independently confirms infection.
            String threat = firstNonBlank(verdict.threatName(), locked.getThreatName(), UNKNOWN_THREAT);
            applyEngineInfo(locked, verdict, durationMs, now);
            locked.setStatus(ScanResultStatus.infected);
            locked.setThreatName(threat);
            locked.setFailureReason(null);
            locked.setNextAttemptAt(now);
            locked.setUpdatedAt(now);
            scanResultRepository.save(locked);
            audit(AuditActionCatalog.DOCUMENT_SCAN_INFECTED, locked, AuditOutcome.SUCCESS, null,
                    scanValues(locked, Map.of("threatName", threat)));
            log.warn("Scan result {} infected ({}) - isolating", scanResultId, threat);
            return true;
        }));
    }

    /** infected -> quarantined. No document row is ever created for this file. */
    private void isolate(ScanResult row, OffsetDateTime leaseToken) {
        StorageLocation staging = loadLocation(row.getStagingLocationId());
        StorageLocation quarantine = storageLocationResolver.resolveForTenant(row.getTenantId(), StoragePurpose.quarantine);
        // Deterministic key: a retry after a crash simply overwrites the same object.
        String quarantineKey = "quarantine/" + row.getTenantId() + "/" + row.getScanResultId();
        storageObjectMover.copyVerified(staging, row.getStagingObjectKey(), quarantine, quarantineKey,
                row.getSizeBytes(), row.getMimeType(), row.getFileHash());

        Boolean committed = transactionTemplate.execute(status -> {
            ScanResult locked = lockOwned(row.getScanResultId(), leaseToken, ScanResultStatus.infected);
            if (locked == null) {
                return false;
            }
            OffsetDateTime now = OffsetDateTime.now();
            QuarantineRecord record = quarantineRecordRepository.findByScanResultId(locked.getScanResultId())
                    .orElseGet(() -> quarantineRecordRepository.save(QuarantineRecord.builder()
                            .tenantId(locked.getTenantId())
                            .scanResultId(locked.getScanResultId())
                            .quarantineLocationId(quarantine.getStorageLocationId())
                            .quarantineObjectKey(quarantineKey)
                            .originalFilename(locked.getOriginalFilename())
                            .fileHash(locked.getFileHash())
                            .sizeBytes(locked.getSizeBytes())
                            .threatName(locked.getThreatName())
                            .status(QuarantineStatus.held)
                            .createdBy(locked.getUploadedBy())
                            .build()));
            locked.setStatus(ScanResultStatus.quarantined);
            locked.setFailureReason(null);
            locked.setLeaseExpiresAt(null);
            locked.setNextAttemptAt(null);
            locked.setUpdatedAt(now);
            scanResultRepository.save(locked);

            auditService.event(AuditActionCatalog.DOCUMENT_QUARANTINED)
                    .tenantId(locked.getTenantId())
                    .objectType(AuditObjectTypes.QUARANTINE_RECORD)
                    .objectId(record.getQuarantineId())
                    .resourceName(locked.getOriginalFilename())
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(Map.of("scanResultId", locked.getScanResultId(), "threatName", locked.getThreatName(),
                            "fileHash", locked.getFileHash(), "uploadedBy", locked.getUploadedBy()))
                    .log();
            // Delivered by ThreatNotificationListener only after this commits.
            eventPublisher.publishEvent(new ThreatQuarantinedEvent(record.getQuarantineId(),
                    locked.getScanResultId(), locked.getTenantId()));
            return true;
        });

        if (Boolean.TRUE.equals(committed)) {
            log.warn("Scan result {} quarantined as {}", row.getScanResultId(), quarantineKey);
            purgeStaging(row.getScanResultId());
        }
    }

    /**
     * pending -> quarantined for a release re-scan that still detects the
     * threat. No new quarantine record, no copy: the original record stays
     * {@code held} and still owns the quarantined object.
     */
    private void refuseRelease(UUID scanResultId, OffsetDateTime leaseToken, ScanVerdict verdict, long durationMs) {
        Boolean committed = transactionTemplate.execute(status -> {
            ScanResult locked = lockOwned(scanResultId, leaseToken, ScanResultStatus.pending);
            if (locked == null) {
                return false;
            }
            OffsetDateTime now = OffsetDateTime.now();
            String threat = firstNonBlank(verdict.threatName(), locked.getThreatName(), UNKNOWN_THREAT);
            applyEngineInfo(locked, verdict, durationMs, now);
            locked.setStatus(ScanResultStatus.quarantined);
            locked.setThreatName(threat);
            locked.setFailureReason("Re-scan still detects " + threat + " - release refused");
            locked.setLeaseExpiresAt(null);
            locked.setNextAttemptAt(null);
            locked.setUpdatedAt(now);
            scanResultRepository.save(locked);
            audit(AuditActionCatalog.DOCUMENT_SCAN_INFECTED, locked, AuditOutcome.SUCCESS, null,
                    scanValues(locked, Map.of("threatName", threat, "releaseAttempt", true)));
            releaseAttemptFinalizer.auditRefused(locked);
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            log.warn("Release re-scan {} still detects a threat - release refused", scanResultId);
            purgeStaging(scanResultId);
        }
    }

    /** Used by the gate too, for files rejected before or while staging. */
    void markUnscannable(UUID scanResultId, OffsetDateTime leaseToken, String reason, ScanVerdict verdict) {
        Boolean committed = transactionTemplate.execute(status -> {
            ScanResult locked = lockOwned(scanResultId, leaseToken, ScanResultStatus.pending);
            if (locked == null) {
                return false;
            }
            OffsetDateTime now = OffsetDateTime.now();
            if (verdict != null) {
                applyEngineInfo(locked, verdict, null, now);
            } else {
                locked.setCompletedAt(now);
            }
            locked.setStatus(ScanResultStatus.unscannable);
            locked.setThreatName(null);
            locked.setFailureReason(truncate(reason));
            locked.setLeaseExpiresAt(null);
            locked.setNextAttemptAt(null);
            locked.setUpdatedAt(now);
            scanResultRepository.save(locked);
            audit(AuditActionCatalog.DOCUMENT_SCAN_UNSCANNABLE, locked, AuditOutcome.FAILURE, reason,
                    scanValues(locked, Map.of()));
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            purgeStaging(scanResultId);
        }
    }

    /** Releases a staged row this caller holds to the retry scheduler, due at once. */
    void handOff(UUID scanResultId, OffsetDateTime leaseToken) {
        reschedule(scanResultId, leaseToken, OffsetDateTime.now());
    }

    private void reschedule(UUID scanResultId, OffsetDateTime leaseToken, OffsetDateTime nextAttemptAt) {
        transactionTemplate.executeWithoutResult(status -> {
            ScanResult locked = lockOwned(scanResultId, leaseToken, ScanResultStatus.pending);
            if (locked == null) {
                return;
            }
            locked.setNextAttemptAt(nextAttemptAt);
            locked.setLeaseExpiresAt(null);
            locked.setUpdatedAt(OffsetDateTime.now());
            scanResultRepository.save(locked);
        });
    }

    /**
     * One failed attempt: back off exponentially, or - once
     * {@code max-attempts} is reached - fail for good (never promoted). An
     * {@code infected} row is never failed: its verdict is known, it only
     * still needs isolating, so it keeps retrying at the maximum backoff.
     */
    void recordFailure(UUID scanResultId, OffsetDateTime leaseToken, String reason) {
        ScanProperties.Retry retry = scanProperties.getRetry();
        Boolean terminal = transactionTemplate.execute(status -> {
            ScanResult locked = scanResultRepository.lockById(scanResultId).orElse(null);
            if (locked == null || !ownsLease(locked, leaseToken)
                    || (locked.getStatus() != ScanResultStatus.pending && locked.getStatus() != ScanResultStatus.infected)) {
                return null;
            }
            OffsetDateTime now = OffsetDateTime.now();
            int attempt = locked.getAttemptCount() + 1;
            boolean giveUp = locked.getStatus() == ScanResultStatus.pending && attempt >= retry.getMaxAttempts();
            locked.setAttemptCount(attempt);
            locked.setFailureReason(truncate(reason));
            locked.setLeaseExpiresAt(null);
            locked.setUpdatedAt(now);
            if (giveUp) {
                locked.setStatus(ScanResultStatus.failed);
                locked.setCompletedAt(now);
                locked.setNextAttemptAt(null);
            } else {
                long backoff = Math.min((long) retry.getInitialBackoffSeconds() << Math.min(attempt - 1, 20),
                        retry.getMaxBackoffSeconds());
                locked.setNextAttemptAt(now.plusSeconds(backoff));
            }
            scanResultRepository.save(locked);
            audit(AuditActionCatalog.DOCUMENT_SCAN_FAILED, locked, AuditOutcome.FAILURE, reason,
                    scanValues(locked, Map.of("attempt", attempt, "terminal", giveUp)));
            return giveUp;
        });
        if (terminal != null) {
            log.warn("Scan result {} attempt failed{}: {}", scanResultId, terminal ? " (terminal)" : "", reason);
        }
    }

    /** Straight to {@code failed} - interrupted staging or no cloud verdict in time. */
    void failTerminal(UUID scanResultId, OffsetDateTime leaseToken, String reason) {
        Boolean committed = transactionTemplate.execute(status -> {
            ScanResult locked = lockOwned(scanResultId, leaseToken, ScanResultStatus.pending);
            if (locked == null) {
                return false;
            }
            OffsetDateTime now = OffsetDateTime.now();
            locked.setStatus(ScanResultStatus.failed);
            locked.setFailureReason(truncate(reason));
            locked.setCompletedAt(now);
            locked.setLeaseExpiresAt(null);
            locked.setNextAttemptAt(null);
            locked.setUpdatedAt(now);
            scanResultRepository.save(locked);
            audit(AuditActionCatalog.DOCUMENT_SCAN_FAILED, locked, AuditOutcome.FAILURE, reason,
                    scanValues(locked, Map.of("attempt", locked.getAttemptCount(), "terminal", true)));
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            log.warn("Scan result {} failed: {}", scanResultId, reason);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Locks the row; null unless it is still in {@code expected} status and this worker's lease is the current one. */
    private ScanResult lockOwned(UUID scanResultId, OffsetDateTime leaseToken, ScanResultStatus expected) {
        ScanResult locked = scanResultRepository.lockById(scanResultId).orElse(null);
        if (locked == null || locked.getStatus() != expected || !ownsLease(locked, leaseToken)) {
            log.info("Scan result {} changed hands or state before this worker could commit - skipping", scanResultId);
            return null;
        }
        return locked;
    }

    private static boolean ownsLease(ScanResult locked, OffsetDateTime leaseToken) {
        OffsetDateTime current = locked.getLeaseExpiresAt();
        return current != null && leaseToken != null && current.isEqual(leaseToken);
    }

    private boolean cloudDeadlinePassed(ScanResult row) {
        OffsetDateTime started = row.getStartedAt() != null ? row.getStartedAt() : row.getCreatedAt();
        return started.plusMinutes(scanProperties.getCloudVerdictTimeoutMinutes()).isBefore(OffsetDateTime.now());
    }

    private static void applyEngineInfo(ScanResult row, ScanVerdict verdict, Long durationMs, OffsetDateTime now) {
        row.setEngineName(verdict.engineName());
        row.setEngineVersion(verdict.engineVersion());
        row.setSignatureVersion(verdict.signatureVersion());
        row.setCompletedAt(verdict.scannedAt() != null ? verdict.scannedAt() : now);
        if (durationMs != null) {
            row.setDurationMs(durationMs);
        }
    }

    private ScanTarget target(ScanResult row, StorageLocation staging) {
        return new ScanTarget(row.getScanResultId(), staging, row.getStagingObjectKey(),
                row.getSizeBytes() == null ? 0 : row.getSizeBytes(), row.getMimeType(), row.getExternalScanId(),
                row.getThreatName());
    }

    StorageLocation loadLocation(UUID storageLocationId) {
        return storageLocationRepository.findByStorageLocationIdAndDeletedFalse(storageLocationId)
                .orElseThrow(() -> new StorageConfigurationException(
                        "Staging location " + storageLocationId + " no longer exists"));
    }

    private ScanResult reload(UUID scanResultId) {
        return scanResultRepository.findById(scanResultId).orElse(null);
    }

    private void audit(String action, ScanResult row, AuditOutcome outcome, String failureReason,
                       Map<String, Object> newValue) {
        auditService.event(action)
                .tenantId(row.getTenantId())
                .objectType(AuditObjectTypes.SCAN_RESULT)
                .objectId(row.getScanResultId())
                .resourceName(row.getOriginalFilename())
                .outcome(outcome)
                .failureReason(failureReason)
                .newValue(newValue)
                .log();
    }

    private static Map<String, Object> scanValues(ScanResult row, Map<String, Object> extra) {
        Map<String, Object> values = new HashMap<>();
        values.put("scanEngineId", row.getScanEngineId());
        values.put("scanMode", row.getScanMode());
        values.put("uploadedBy", row.getUploadedBy());
        Optional.ofNullable(row.getFileHash()).ifPresent(v -> values.put("fileHash", v));
        Optional.ofNullable(row.getEngineName()).ifPresent(v -> values.put("engineName", v));
        Optional.ofNullable(row.getEngineVersion()).ifPresent(v -> values.put("engineVersion", v));
        Optional.ofNullable(row.getSignatureVersion()).ifPresent(v -> values.put("signatureVersion", v));
        Optional.ofNullable(row.getDurationMs()).ifPresent(v -> values.put("durationMs", v));
        values.putAll(extra);
        return values;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    static boolean sameETag(String a, String b) {
        if (a == null || b == null) {
            return true;
        }
        return Objects.equals(a.replace("\"", "").toLowerCase(), b.replace("\"", "").toLowerCase());
    }
}
