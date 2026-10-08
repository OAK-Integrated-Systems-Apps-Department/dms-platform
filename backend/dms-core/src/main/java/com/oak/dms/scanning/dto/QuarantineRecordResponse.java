package com.oak.dms.scanning.dto;

import com.oak.dms.scanning.enums.QuarantineStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the quarantine list. Never carries a storage location or
 * object key - quarantined bytes are not reachable through any API.
 */
public record QuarantineRecordResponse(
        UUID quarantineId,
        String originalFilename,
        String threatName,
        long sizeBytes,
        QuarantineStatus status,
        OffsetDateTime detectedAt,
        QuarantineUserSummary uploadedBy,
        QuarantineUserSummary reviewedBy,
        OffsetDateTime reviewedAt
) {
}
