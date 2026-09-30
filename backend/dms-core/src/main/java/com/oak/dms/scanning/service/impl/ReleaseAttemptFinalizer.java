package com.oak.dms.scanning.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.QuarantineStatus;
import com.oak.dms.scanning.repository.QuarantineRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Closes out a quarantine <em>release attempt</em> - a {@link ScanResult}
 * whose {@code releasedFromScanResultId} points at an original, quarantined
 * scan result - from inside {@link IngestionProcessor}'s own transactions.
 * The re-scan runs through the ordinary scan gate (so a cloud re-scan can
 * finish in the retry scheduler or a callback, long after the admin's
 * request returned); this is the only place its outcome touches the
 * quarantine record:
 * <ul>
 *   <li>re-scan clean and promoted -> {@link #markReleased}: record
 *   {@code released}, audit {@code QUARANTINE_RELEASED};</li>
 *   <li>re-scan still infected -> {@link #auditRefused}: record stays
 *   {@code held}, audit {@code QUARANTINE_RELEASE_REFUSED}.</li>
 * </ul>
 * Both must be called inside the caller's transaction.
 */
@Component
@RequiredArgsConstructor
class ReleaseAttemptFinalizer {

    private final QuarantineRecordRepository quarantineRecordRepository;
    private final AuditService auditService;

    /** What the caller must delete from quarantine storage once its transaction has committed. */
    record ReleasedObject(UUID quarantineLocationId, String quarantineObjectKey) {
    }

    /**
     * @throws IllegalStateException if the quarantine record is no longer
     *         {@code held} - the whole promotion then rolls back and is retried
     */
    ReleasedObject markReleased(ScanResult attempt, UUID documentId) {
        QuarantineRecord record = lockHeldRecord(attempt);
        OffsetDateTime now = OffsetDateTime.now();
        record.setStatus(QuarantineStatus.released);
        record.setReviewedBy(attempt.getReviewRequestedBy());
        record.setReviewedAt(now);
        record.setReviewReason(attempt.getReviewReason());
        record.setReleasedDocumentId(documentId);
        record.setUpdatedBy(attempt.getReviewRequestedBy());
        record.setUpdatedAt(now);
        quarantineRecordRepository.save(record);

        Map<String, Object> values = baseValues(record, attempt);
        values.put("documentId", documentId);
        Optional.ofNullable(attempt.getEngineName()).ifPresent(v -> values.put("engineName", v));
        Optional.ofNullable(attempt.getEngineVersion()).ifPresent(v -> values.put("engineVersion", v));
        Optional.ofNullable(attempt.getSignatureVersion()).ifPresent(v -> values.put("signatureVersion", v));
        audit(AuditActionCatalog.QUARANTINE_RELEASED, record, attempt, AuditOutcome.SUCCESS, values);
        return new ReleasedObject(record.getQuarantineLocationId(), record.getQuarantineObjectKey());
    }

    /** The re-scan still detects the threat: the file stays exactly where it is, under its original record. */
    void auditRefused(ScanResult attempt) {
        QuarantineRecord record = quarantineRecordRepository.findByScanResultId(attempt.getReleasedFromScanResultId())
                .orElseThrow(() -> new IllegalStateException("No quarantine record for scan result "
                        + attempt.getReleasedFromScanResultId()));
        Map<String, Object> values = baseValues(record, attempt);
        values.put("redetectedThreatName", attempt.getThreatName());
        Optional.ofNullable(attempt.getSignatureVersion()).ifPresent(v -> values.put("signatureVersion", v));
        audit(AuditActionCatalog.QUARANTINE_RELEASE_REFUSED, record, attempt, AuditOutcome.FAILURE, values);
    }

    private QuarantineRecord lockHeldRecord(ScanResult attempt) {
        QuarantineRecord record = quarantineRecordRepository
                .lockByScanResultId(attempt.getReleasedFromScanResultId())
                .orElseThrow(() -> new IllegalStateException("No quarantine record for scan result "
                        + attempt.getReleasedFromScanResultId()));
        if (record.getStatus() != QuarantineStatus.held || record.isDeleted()) {
            throw new IllegalStateException("Quarantine record " + record.getQuarantineId()
                    + " is no longer held (" + record.getStatus() + ")");
        }
        return record;
    }

    private static Map<String, Object> baseValues(QuarantineRecord record, ScanResult attempt) {
        Map<String, Object> values = new HashMap<>();
        values.put("threatName", record.getThreatName());
        values.put("fileHash", record.getFileHash());
        values.put("reviewer", attempt.getReviewRequestedBy());
        values.put("releaseScanResultId", attempt.getScanResultId());
        values.put("originalScanResultId", record.getScanResultId());
        return values;
    }

    private void audit(String action, QuarantineRecord record, ScanResult attempt, AuditOutcome outcome,
                       Map<String, Object> values) {
        // Actor defaults to the request's user (the reviewer, for a synchronous
        // ClamAV re-scan) or SYSTEM (a cloud re-scan finishing later); the
        // reviewer is always in newValue.
        auditService.event(action)
                .tenantId(record.getTenantId())
                .objectType(AuditObjectTypes.QUARANTINE_RECORD)
                .objectId(record.getQuarantineId())
                .resourceName(record.getOriginalFilename())
                .outcome(outcome)
                .reason(attempt.getReviewReason())
                .newValue(values)
                .log();
    }
}
