package com.oak.dms.scanning.dto;

import com.oak.dms.scanning.enums.ScanVerdictStatus;

import java.time.OffsetDateTime;

/**
 * Provider-independent outcome of one {@link com.oak.dms.scanning.service.ScanService#scan}
 * call. Engine failures are never a verdict - they are
 * {@link com.oak.dms.scanning.exception.ScanException}s.
 * <p>
 * {@code engineVersion}/{@code signatureVersion} are null for the cloud
 * scanners: neither Defender for Storage nor GuardDuty Malware Protection
 * exposes them.
 */
public record ScanVerdict(
        ScanVerdictStatus status,
        String threatName,
        String externalScanId,
        String engineName,
        String engineVersion,
        String signatureVersion,
        OffsetDateTime scannedAt,
        String detail
) {

    public static ScanVerdict pending(String engineName) {
        return new ScanVerdict(ScanVerdictStatus.PENDING, null, null, engineName, null, null, null, null);
    }
}
