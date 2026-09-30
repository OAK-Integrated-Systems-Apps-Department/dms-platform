package com.oak.dms.scanning.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.scanning.dto.QuarantineReleaseResponse;
import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.QuarantineStatus;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.QuarantineStateException;
import com.oak.dms.scanning.helper.QuarantineMapper;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.helper.StorageObjectMover;
import com.oak.dms.scanning.repository.QuarantineRecordRepository;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.scanning.service.ScanEngineFactory;
import com.oak.dms.scanning.service.ScanEngineResolver;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.exception.StorageException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Releasing a false positive from quarantine (VMSBDI alternate flow 3).
 * Two routes, both recorded as a <em>release attempt</em> - a
 * {@code scan_results} row whose {@code releasedFromScanResultId} points at
 * the original, quarantined result:
 * <ol>
 *   <li><b>Re-scan</b> ({@link #release}, {@code docm.quarantine.release}):
 *   the quarantined bytes are copied back into staging (hash-verified) and
 *   run through the ordinary scan gate on the tenant's <em>current</em>
 *   engine and signatures. Clean -> promoted by {@link DocumentPromoter} like
 *   any upload, and the record closed out by {@link ReleaseAttemptFinalizer}
 *   in the same transaction. Still infected -> refused; the record stays
 *   {@code held}. A cloud re-scan returns {@code pending} and finishes in
 *   the retry scheduler or a callback.</li>
 *   <li><b>Override</b> ({@link #overrideRelease}, {@code docm.quarantine.override}):
 *   only once a re-scan has been refused and nothing is in flight. Promotes
 *   the file as an {@code overridden} version, backed by a
 *   {@code released_override} scan result - the one pairing V40's widened
 *   gate trigger accepts besides clean, and only when it is tied to this
 *   reviewed, released quarantine record with the same file hash.</li>
 * </ol>
 * The quarantined object is deleted once a release commits; it now lives in
 * the document location.
 */
@Slf4j
@Component
class QuarantineReleaseService {

    private final QuarantineRecordRepository quarantineRecordRepository;
    private final ScanResultRepository scanResultRepository;
    private final ScanEngineResolver scanEngineResolver;
    private final ScanEngineFactory scanEngineFactory;
    private final StorageLocationResolver storageLocationResolver;
    private final StorageLocationRepository storageLocationRepository;
    private final StorageObjectMover storageObjectMover;
    private final IngestionProcessor ingestionProcessor;
    private final DocumentPromoter documentPromoter;
    private final AuditService auditService;
    private final ScanProperties scanProperties;
    private final TransactionTemplate transactionTemplate;

    QuarantineReleaseService(QuarantineRecordRepository quarantineRecordRepository,
                             ScanResultRepository scanResultRepository,
                             ScanEngineResolver scanEngineResolver,
                             ScanEngineFactory scanEngineFactory,
                             StorageLocationResolver storageLocationResolver,
                             StorageLocationRepository storageLocationRepository,
                             StorageObjectMover storageObjectMover,
                             IngestionProcessor ingestionProcessor,
                             DocumentPromoter documentPromoter,
                             AuditService auditService,
                             ScanProperties scanProperties,
                             PlatformTransactionManager transactionManager) {
        this.quarantineRecordRepository = quarantineRecordRepository;
        this.scanResultRepository = scanResultRepository;
        this.scanEngineResolver = scanEngineResolver;
        this.scanEngineFactory = scanEngineFactory;
        this.storageLocationResolver = storageLocationResolver;
        this.storageLocationRepository = storageLocationRepository;
        this.storageObjectMover = storageObjectMover;
        this.ingestionProcessor = ingestionProcessor;
        this.documentPromoter = documentPromoter;
        this.auditService = auditService;
        this.scanProperties = scanProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // Re-scan release
    // ------------------------------------------------------------------

    QuarantineReleaseResponse release(UUID quarantineId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // 1. Lock the record, check it, and create the leased release attempt.
        ScanResult attempt = transactionTemplate.execute(status -> {
            QuarantineRecord record = lockOwned(quarantineId, tenantId);
            QuarantineServiceImpl.requireHeld(record);
            requireNothingInFlight(record);
            ScanResult original = loadOriginal(record);

            ScanEngine engine = scanEngineResolver.resolveForTenant(tenantId);
            StorageLocation staging = storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging);
            scanEngineFactory.resolveCompatible(engine, staging);

            OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
            ScanResult saved = scanResultRepository.saveAndFlush(ScanResult.builder()
                    .tenantId(tenantId)
                    .scanEngineId(engine.getScanEngineId())
                    .stagingLocationId(staging.getStorageLocationId())
                    .originalFilename(record.getOriginalFilename())
                    .mimeType(original.getMimeType())
                    .status(ScanResultStatus.pending)
                    .scanMode(engine.getScanMode())
                    .startedAt(now)
                    .uploadedBy(original.getUploadedBy())
                    .nextAttemptAt(now)
                    .leaseExpiresAt(now.plusSeconds(scanProperties.getRetry().getLeaseSeconds()))
                    .ingestionKind(original.getIngestionKind())
                    .createdReason(original.getCreatedReason())
                    .documentTypeId(original.getDocumentTypeId())
                    .targetDocumentId(original.getTargetDocumentId())
                    .title(original.getTitle())
                    .releasedFromScanResultId(original.getScanResultId())
                    .reviewRequestedBy(userId)
                    .reviewReason(reason)
                    .createdBy(userId)
                    .createdAt(now)
                    .build());
            saved.setStagingObjectKey("staging/" + tenantId + "/" + saved.getScanResultId());
            return scanResultRepository.saveAndFlush(saved);
        });
        UUID attemptId = attempt.getScanResultId();
        OffsetDateTime leaseToken = attempt.getLeaseExpiresAt();

        // 2. Copy the quarantined bytes back into staging, verified against the
        //    hash recorded when they were first scanned. For a cloud engine this
        //    write is itself the re-submission to the provider's scanner.
        QuarantineRecord record = quarantineRecordRepository.findByScanResultId(attempt.getReleasedFromScanResultId())
                .orElseThrow();
        StorageLocation staging = ingestionProcessor.loadLocation(attempt.getStagingLocationId());
        StoredObjectMetadata staged;
        try {
            staged = storageObjectMover.copyVerified(quarantineLocation(record), record.getQuarantineObjectKey(),
                    staging, attempt.getStagingObjectKey(), record.getSizeBytes(), attempt.getMimeType(),
                    record.getFileHash());
        } catch (StorageException | StorageObjectMover.StagedObjectChangedException e) {
            storageObjectMover.deleteQuietly(staging, attempt.getStagingObjectKey());
            ingestionProcessor.failTerminal(attemptId, leaseToken,
                    "Could not copy the quarantined file for re-scan: " + e.getMessage());
            return outcome(attemptId);
        }
        transactionTemplate.executeWithoutResult(status -> scanResultRepository.lockById(attemptId).ifPresent(locked -> {
            locked.setFileHash(record.getFileHash());
            locked.setSizeBytes(record.getSizeBytes());
            locked.setExternalScanId(staged != null ? staged.contentHash() : null);
            locked.setUpdatedAt(OffsetDateTime.now());
            scanResultRepository.save(locked);
        }));

        // 3. Through the ordinary gate: ClamAV answers now, cloud comes back pending.
        ingestionProcessor.process(attemptId);
        return outcome(attemptId);
    }

    private QuarantineReleaseResponse outcome(UUID attemptId) {
        ScanResult attempt = scanResultRepository.findById(attemptId).orElseThrow();
        return switch (attempt.getStatus()) {
            case clean -> new QuarantineReleaseResponse("released", attemptId,
                    documentPromoter.findDocumentIdForVersion(attempt.getTenantId(), attempt.getDocumentVersionId())
                            .orElse(null),
                    null, "The re-scan came back clean and the file was released into the repository.");
            case quarantined -> new QuarantineReleaseResponse("refused", attemptId, null, attempt.getThreatName(),
                    "The re-scan still detects " + attempt.getThreatName()
                            + ". The file stays in quarantine; an administrator with override permission may force-release it.");
            case pending, infected -> new QuarantineReleaseResponse("pending", attemptId, null, null,
                    attempt.getAttemptCount() > 0
                            ? "The scan service is unavailable; the re-scan will be retried automatically."
                            : "The file is being re-scanned. Check back shortly.");
            case failed, unscannable, released_override -> new QuarantineReleaseResponse("failed", attemptId, null, null,
                    "The file could not be re-scanned (" + attempt.getFailureReason() + "). It stays in quarantine.");
        };
    }

    // ------------------------------------------------------------------
    // Override release
    // ------------------------------------------------------------------

    QuarantineReleaseResponse overrideRelease(UUID quarantineId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // 1. Preconditions, before any bytes move.
        QuarantineRecord record = transactionTemplate.execute(status -> {
            QuarantineRecord locked = lockOwned(quarantineId, tenantId);
            requireOverrideAllowed(locked);
            return locked;
        });
        ScanResult original = loadOriginal(record);

        // 2. Plan the document and copy the bytes straight from quarantine,
        //    hash-verified - these are exactly the bytes the scanner flagged.
        // Not saved yet - the insert (and its generated id) happens in step 3.
        ScanResult draft = overrideRow(original, record, userId, reason);
        DocumentPromoter.PromotionPlan plan = documentPromoter.prepare(draft);
        draft.setDocumentVersionId(plan.versionId());
        storageObjectMover.copyVerified(quarantineLocation(record), record.getQuarantineObjectKey(),
                plan.documentLocation(), plan.objectKey(), record.getSizeBytes(), original.getMimeType(),
                record.getFileHash());

        // 3. One transaction: override row + version + record released + audit.
        //    V40's gate trigger checks the whole chain at commit.
        List<UUID> refusedAttempts;
        try {
            refusedAttempts = transactionTemplate.execute(status -> {
                QuarantineRecord locked = lockOwned(quarantineId, tenantId);
                List<UUID> refused = requireOverrideAllowed(locked);
                OffsetDateTime now = OffsetDateTime.now();

                scanResultRepository.saveAndFlush(draft);
                documentPromoter.persist(plan, draft);

                locked.setStatus(QuarantineStatus.released);
                locked.setReviewedBy(userId);
                locked.setReviewedAt(now);
                locked.setReviewReason(reason);
                locked.setReleasedDocumentId(plan.documentId());
                locked.setUpdatedBy(userId);
                locked.setUpdatedAt(now);
                quarantineRecordRepository.save(locked);

                auditService.event(AuditActionCatalog.QUARANTINE_OVERRIDE_RELEASED)
                        .objectType(AuditObjectTypes.QUARANTINE_RECORD)
                        .objectId(locked.getQuarantineId())
                        .resourceName(locked.getOriginalFilename())
                        .outcome(AuditOutcome.SUCCESS)
                        .reason(reason)
                        .newValue(Map.of("threatName", locked.getThreatName(), "fileHash", locked.getFileHash(),
                                "reviewer", userId, "overrideScanResultId", draft.getScanResultId(),
                                "refusedAttemptIds", refused, "documentId", plan.documentId(),
                                "versionId", plan.versionId()))
                        .log();
                auditService.event(AuditActionCatalog.DOCUMENT_UPLOADED)
                        .objectType(AuditObjectTypes.DOCUMENT)
                        .objectId(plan.documentId())
                        .resourceName(draft.getTitle() != null ? draft.getTitle() : draft.getOriginalFilename())
                        .outcome(AuditOutcome.SUCCESS)
                        .newValue(Map.of("versionId", plan.versionId(), "sizeBytes", draft.getSizeBytes(),
                                "scanResultId", draft.getScanResultId(), "uploadedBy", draft.getUploadedBy(),
                                "scanStatus", "overridden"))
                        .log();
                return refused;
            });
        } catch (RuntimeException e) {
            storageObjectMover.deleteQuietly(plan.documentLocation(), plan.objectKey());
            throw e;
        }

        log.warn("Quarantine record {} force-released by {} as document {} (refused re-scans: {})",
                quarantineId, userId, plan.documentId(), refusedAttempts);
        storageObjectMover.deleteQuietly(quarantineLocation(record), record.getQuarantineObjectKey());
        return new QuarantineReleaseResponse("released", draft.getScanResultId(), plan.documentId(), record.getThreatName(),
                "The file was force-released into the repository by administrator override.");
    }

    /** @return the refused re-scan attempt ids the override rests on */
    private List<UUID> requireOverrideAllowed(QuarantineRecord record) {
        QuarantineServiceImpl.requireHeld(record);
        List<ScanResult> attempts = scanResultRepository
                .findByReleasedFromScanResultIdOrderByCreatedAtDesc(record.getScanResultId());
        if (attempts.stream().anyMatch(a -> QuarantineMapper.ACTIVE_RELEASE_STATUSES.contains(a.getStatus()))) {
            throw new QuarantineStateException("QUARANTINE_RELEASE_IN_PROGRESS",
                    "A release of this file is already in progress");
        }
        List<UUID> refused = attempts.stream()
                .filter(a -> a.getStatus() == ScanResultStatus.quarantined)
                .map(ScanResult::getScanResultId)
                .toList();
        if (refused.isEmpty()) {
            throw new QuarantineStateException("QUARANTINE_OVERRIDE_REQUIRES_RESCAN",
                    "Release the file with a re-scan first - an override is only allowed once a re-scan has "
                            + "confirmed the scanner still flags it");
        }
        return refused;
    }

    private void requireNothingInFlight(QuarantineRecord record) {
        boolean inFlight = scanResultRepository
                .findByReleasedFromScanResultIdOrderByCreatedAtDesc(record.getScanResultId()).stream()
                .anyMatch(a -> QuarantineMapper.ACTIVE_RELEASE_STATUSES.contains(a.getStatus()));
        if (inFlight) {
            throw new QuarantineStateException("QUARANTINE_RELEASE_IN_PROGRESS",
                    "A release of this file is already in progress");
        }
    }

    private ScanResult overrideRow(ScanResult original, QuarantineRecord record, UUID userId, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        return ScanResult.builder()
                .tenantId(original.getTenantId())
                // The verdict being overridden: the original engine, staging and scan details.
                .scanEngineId(original.getScanEngineId())
                .stagingLocationId(original.getStagingLocationId())
                .originalFilename(record.getOriginalFilename())
                .fileHash(record.getFileHash())
                .sizeBytes(record.getSizeBytes())
                .mimeType(original.getMimeType())
                .status(ScanResultStatus.released_override)
                .threatName(record.getThreatName())
                .engineName(original.getEngineName())
                .engineVersion(original.getEngineVersion())
                .signatureVersion(original.getSignatureVersion())
                .scanMode(original.getScanMode())
                .startedAt(now)
                .completedAt(now)
                .uploadedBy(original.getUploadedBy())
                .ingestionKind(original.getIngestionKind())
                .createdReason(original.getCreatedReason())
                .documentTypeId(original.getDocumentTypeId())
                .targetDocumentId(original.getTargetDocumentId())
                .title(original.getTitle())
                .releasedFromScanResultId(original.getScanResultId())
                .reviewRequestedBy(userId)
                .reviewReason(reason)
                .createdBy(userId)
                .createdAt(now)
                .build();
    }

    // ------------------------------------------------------------------

    private QuarantineRecord lockOwned(UUID quarantineId, UUID tenantId) {
        return quarantineRecordRepository.lockByIdAndTenant(quarantineId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("QuarantineRecord", quarantineId));
    }

    private ScanResult loadOriginal(QuarantineRecord record) {
        return scanResultRepository.findById(record.getScanResultId())
                .orElseThrow(() -> new IllegalStateException("Quarantine record " + record.getQuarantineId()
                        + " has no scan result"));
    }

    private StorageLocation quarantineLocation(QuarantineRecord record) {
        return storageLocationRepository.findByStorageLocationIdAndDeletedFalse(record.getQuarantineLocationId())
                .orElseThrow(() -> new StorageConfigurationException("Quarantine location "
                        + record.getQuarantineLocationId() + " no longer exists"));
    }
}
