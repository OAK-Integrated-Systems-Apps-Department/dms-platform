package com.oak.dms.scanning.enums;

/**
 * Which {@link com.oak.dms.scanning.service.ScanService} adapter a
 * {@link com.oak.dms.scanning.entity.ScanEngine} resolves to, via
 * {@link com.oak.dms.scanning.service.ScanEngineFactory}. Adding a provider
 * means a value here, a matching {@code ck_scan_engines_provider} CHECK value
 * in the next migration, and a new adapter bean - never a change to the
 * ingestion gate or any other business logic.
 */
public enum ScanProvider {
    /** ClamAV {@code clamd}, INSTREAM over TCP - on-premise, synchronous. */
    CLAMAV,
    /** Microsoft Defender for Storage malware scanning - Azure, asynchronous. */
    AZURE_DEFENDER,
    /** AWS GuardDuty Malware Protection for S3 - asynchronous. */
    AWS_GUARDDUTY;

    public ScanMode scanMode() {
        return this == CLAMAV ? ScanMode.pre_store_stream : ScanMode.post_store_object;
    }
}
