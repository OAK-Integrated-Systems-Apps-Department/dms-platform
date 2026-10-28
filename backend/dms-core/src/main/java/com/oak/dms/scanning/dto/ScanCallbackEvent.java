package com.oak.dms.scanning.dto;

import java.util.List;

/**
 * One cloud scan-result notification, reduced to what the gate needs to find
 * the scan_results row it belongs to. Untrusted: it only ever makes a row due
 * for re-evaluation - the verdict itself is re-read from the provider.
 *
 * @param container      S3 bucket or Azure blob container of the scanned object
 * @param objectKey      the staged object key ({@code staging/<tenantId>/<scanResultId>})
 * @param eTag           the scanned object's ETag, compared to {@code scan_results.external_scan_id}
 * @param providerScanId the provider's own correlation id (Defender {@code correlationId}, EventBridge event {@code id}), for the audit trail
 */
public record ScanCallbackEvent(
        String container,
        String objectKey,
        String eTag,
        List<String> threatNames,
        String providerScanId
) {
}
