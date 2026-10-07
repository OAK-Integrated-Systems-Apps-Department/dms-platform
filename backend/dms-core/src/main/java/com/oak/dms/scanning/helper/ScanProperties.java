package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.enums.ScanProvider;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code dms.scanning.*} configuration keys - the deployment-wide
 * defaults {@link ScanEngineBootstrap} seeds the platform-default
 * {@link com.oak.dms.scanning.entity.ScanEngine} from, and that each adapter
 * falls back to for any engine whose {@code endpoint}/{@code region}/
 * {@code credentialRef} is blank. Mirrors
 * {@link com.oak.dms.storage.helper.StorageProperties}. Never logged in full
 * (holds cloud credentials and callback secrets).
 * <p>
 * The staging and quarantine storage targets live under
 * {@code dms.storage.staging.*}/{@code dms.storage.quarantine.*} - they are
 * storage locations, seeded by {@code StorageLocationBootstrap}.
 */
@Component
@ConfigurationProperties(prefix = "dms.scanning")
@Getter
@Setter
public class ScanProperties {

    private ScanProvider defaultProvider = ScanProvider.CLAMAV;
    private int timeoutSeconds = 900;
    /**
     * No limit by default. ClamAV reads at most 2147483645 bytes of one stream, so larger
     * files are scanned in overlapping segments (see {@link ClamAv#getSegmentBytes()}).
     * An engine row can still be given a lower limit.
     */
    private long maxFileSizeBytes = Long.MAX_VALUE;
    /** A cloud row still without a verdict this long after staging is failed, never promoted. */
    private int cloudVerdictTimeoutMinutes = 60;
    /** How long a failed row's staged object is kept (for Phase 2 re-queue) before the purge sweep deletes it. */
    private int failedRetentionHours = 72;
    private final Retry retry = new Retry();
    private final ClamAv clamav = new ClamAv();
    private final AzureDefender azureDefender = new AzureDefender();
    private final AwsGuardDuty awsGuardduty = new AwsGuardDuty();
    private final Notifications notifications = new Notifications();

    @Getter
    @Setter
    public static class Retry {
        private long pollIntervalMs = 30_000;
        private int maxAttempts = 10;
        private int initialBackoffSeconds = 30;
        private int maxBackoffSeconds = 900;
        /** Must comfortably exceed timeoutSeconds plus the staging -> document/quarantine copy time. */
        private int leaseSeconds = 1_800;
        /**
         * Added to {@link #leaseSeconds} for every GB of the file, so a lease on a very large
         * file (scanned segment by segment, then copied) doesn't run out mid-way and let a
         * second worker take the row over.
         */
        private int leaseSecondsPerGb = 120;
        private int batchSize = 20;

        /** The lease for a file of {@code sizeBytes} (0 or less if not known yet). */
        public long leaseSecondsFor(long sizeBytes) {
            long gigabytes = Math.max(0, sizeBytes) / (1024L * 1024 * 1024);
            return leaseSeconds + gigabytes * leaseSecondsPerGb;
        }
    }

    /** Threat-detected emails to the uploader and administrators (VMSBDI FR-003). */
    @Getter
    @Setter
    public static class Notifications {
        private boolean enabled = true;
        private String mailFrom = "no-reply@oakdms.local";
    }

    @Getter
    @Setter
    public static class ClamAv {
        private String host = "localhost";
        private int port = 3310;
        private int connectTimeoutMs = 5_000;
        private int chunkSizeBytes = 65_536;
        /**
         * Largest piece sent to clamd in one {@code INSTREAM}: 2000 MB, under the 2147483645
         * bytes clamd reads of one stream (and its StreamMaxLength of 2047M). A larger file
         * goes over in several segments.
         */
        private long segmentBytes = 2_097_152_000L;
        /** How much of the end of one segment starts the next, so no signature is cut in two. Held in memory. */
        private int segmentOverlapBytes = 16 * 1024 * 1024;
        /**
         * The most clamd unpacks of one archive - its MaxScanSize (4000M). A ZIP that unpacks to
         * more (or is larger than one segment) is opened by dms-core and scanned file by file.
         */
        private long archiveScanBytes = 4_194_304_000L;
        /** The most files clamd opens in one archive - its MaxFiles. More, and the ZIP is scanned file by file too. */
        private int archiveScanFiles = 10_000;
    }

    @Getter
    @Setter
    public static class AzureDefender {
        /** Blob Service endpoint of the Defender-protected storage account holding the staging container. */
        private String endpoint;
        private String accountName;
        private String accountKey;
        /** Shared secret Event Grid sends in X-Oak-Scan-Callback-Secret. Blank disables the callback endpoint. */
        private String callbackSecret;
    }

    @Getter
    @Setter
    public static class AwsGuardDuty {
        private String region = "us-east-1";
        private String accessKey;
        private String secretKey;
        /** Shared secret the EventBridge API destination sends in X-Oak-Scan-Callback-Secret. Blank disables the callback endpoint. */
        private String callbackSecret;
    }
}
