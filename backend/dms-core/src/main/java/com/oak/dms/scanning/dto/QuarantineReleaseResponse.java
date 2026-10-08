package com.oak.dms.scanning.dto;

import java.util.UUID;

/**
 * Result of a release or override request.
 *
 * @param outcome {@code released} (promoted - documentId set), {@code refused} (re-scan still
 *                detects the threat), {@code pending} (cloud re-scan running - poll the record),
 *                {@code failed} (could not be re-scanned; the file stays held)
 */
public record QuarantineReleaseResponse(
        String outcome,
        UUID attemptScanResultId,
        UUID documentId,
        String threatName,
        String message
) {
}
