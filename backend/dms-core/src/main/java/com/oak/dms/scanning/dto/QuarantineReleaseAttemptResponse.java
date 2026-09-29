package com.oak.dms.scanning.dto;

import com.oak.dms.scanning.enums.ScanResultStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One release attempt for a quarantined file.
 *
 * @param kind   {@code rescan} or {@code override}
 * @param status {@code pending} (re-scan running), {@code clean} (released),
 *               {@code quarantined} (re-scan still detects the threat - refused),
 *               {@code released_override}, {@code failed}/{@code unscannable}
 */
public record QuarantineReleaseAttemptResponse(
        UUID scanResultId,
        String kind,
        ScanResultStatus status,
        QuarantineUserSummary requestedBy,
        String reason,
        String engineName,
        String signatureVersion,
        String threatName,
        String failureReason,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt
) {
}
