package com.oak.dms.scanning.dto;

import com.oak.dms.scanning.enums.QuarantineStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A quarantine record with the scan that caught it and its release history.
 *
 * @param releaseInProgress  a re-scan release is still running (cloud scanners, or a retry)
 * @param overrideAvailable  a re-scan was refused and nothing is in flight - an override is permitted
 */
public record QuarantineRecordDetailResponse(
        UUID quarantineId,
        String originalFilename,
        String threatName,
        long sizeBytes,
        String fileHash,
        QuarantineStatus status,
        OffsetDateTime detectedAt,
        QuarantineUserSummary uploadedBy,
        QuarantineUserSummary reviewedBy,
        OffsetDateTime reviewedAt,
        String reviewReason,
        UUID releasedDocumentId,
        QuarantineScanDetail scan,
        boolean releaseInProgress,
        boolean overrideAvailable,
        List<QuarantineReleaseAttemptResponse> releaseAttempts
) {
}
