package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.dto.QuarantineRecordDetailResponse;
import com.oak.dms.scanning.dto.QuarantineRecordResponse;
import com.oak.dms.scanning.dto.QuarantineReleaseAttemptResponse;
import com.oak.dms.scanning.dto.QuarantineScanDetail;
import com.oak.dms.scanning.dto.QuarantineUserSummary;
import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.QuarantineStatus;
import com.oak.dms.scanning.enums.ScanResultStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quarantine entities -> API responses. Hand-written rather than MapStruct
 * (unlike {@code StorageLocationMapper}): the detail response is assembled
 * from three sources - the record, its original scan result and its release
 * attempts - plus a batch-loaded user lookup, which MapStruct would only
 * obscure. Never maps a storage location id or object key.
 */
@Component
public class QuarantineMapper {

    /** Attempt statuses that block another release (in flight or already released) - mirrors V40's uq_scan_results_one_active_release. */
    public static final Set<ScanResultStatus> ACTIVE_RELEASE_STATUSES = Set.of(
            ScanResultStatus.pending, ScanResultStatus.infected, ScanResultStatus.clean,
            ScanResultStatus.released_override);

    public QuarantineRecordResponse toListRow(QuarantineRecord record, Map<UUID, QuarantineUserSummary> users) {
        return new QuarantineRecordResponse(
                record.getQuarantineId(),
                record.getOriginalFilename(),
                record.getThreatName(),
                record.getSizeBytes(),
                record.getStatus(),
                record.getCreatedAt(),
                user(record.getCreatedBy(), users),
                user(record.getReviewedBy(), users),
                record.getReviewedAt());
    }

    public QuarantineRecordDetailResponse toDetail(QuarantineRecord record, ScanResult original,
                                                   List<ScanResult> attempts, Map<UUID, QuarantineUserSummary> users) {
        boolean inProgress = attempts.stream().anyMatch(a -> a.getStatus() == ScanResultStatus.pending
                || a.getStatus() == ScanResultStatus.infected);
        boolean held = record.getStatus() == QuarantineStatus.held;
        return new QuarantineRecordDetailResponse(
                record.getQuarantineId(),
                record.getOriginalFilename(),
                record.getThreatName(),
                record.getSizeBytes(),
                record.getFileHash(),
                record.getStatus(),
                record.getCreatedAt(),
                user(original != null ? original.getUploadedBy() : record.getCreatedBy(), users),
                user(record.getReviewedBy(), users),
                record.getReviewedAt(),
                record.getReviewReason(),
                record.getReleasedDocumentId(),
                original == null ? null : new QuarantineScanDetail(
                        original.getScanResultId(),
                        original.getThreatName(),
                        original.getEngineName(),
                        original.getEngineVersion(),
                        original.getSignatureVersion(),
                        original.getScanMode(),
                        original.getCompletedAt(),
                        original.getDurationMs(),
                        original.getMimeType()),
                inProgress,
                held && !inProgress && hasRefusedRescan(attempts),
                attempts.stream().map(a -> toAttempt(a, users)).toList());
    }

    public static boolean hasRefusedRescan(List<ScanResult> attempts) {
        return attempts.stream().anyMatch(a -> a.getStatus() == ScanResultStatus.quarantined);
    }

    private static QuarantineReleaseAttemptResponse toAttempt(ScanResult attempt, Map<UUID, QuarantineUserSummary> users) {
        return new QuarantineReleaseAttemptResponse(
                attempt.getScanResultId(),
                attempt.getStatus() == ScanResultStatus.released_override ? "override" : "rescan",
                attempt.getStatus(),
                user(attempt.getReviewRequestedBy(), users),
                attempt.getReviewReason(),
                attempt.getEngineName(),
                attempt.getSignatureVersion(),
                attempt.getThreatName(),
                attempt.getFailureReason(),
                attempt.getCreatedAt(),
                attempt.getCompletedAt());
    }

    private static QuarantineUserSummary user(UUID userId, Map<UUID, QuarantineUserSummary> users) {
        if (userId == null) {
            return null;
        }
        return users.getOrDefault(userId, new QuarantineUserSummary(userId, null));
    }
}
