package com.oak.dms.scanning.dto;

import com.oak.dms.scanning.enums.ScanMode;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The original scan that quarantined the file. engine/signature versions are null for the cloud scanners. */
public record QuarantineScanDetail(
        UUID scanResultId,
        String threatName,
        String engineName,
        String engineVersion,
        String signatureVersion,
        ScanMode scanMode,
        OffsetDateTime scannedAt,
        Long durationMs,
        String mimeType
) {
}
