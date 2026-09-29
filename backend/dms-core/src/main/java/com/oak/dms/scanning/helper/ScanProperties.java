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
    private int timeoutSeconds = 60;
    private long maxFileSizeBytes = 104_857_600L;
    /** A cloud row still without a verdict this long after staging is failed, never promoted. */
    private int cloudVerdictTimeoutMinutes = 60;
    /** How long a failed row's staged object is kept (for Phase 2 re-queue) before the purge sweep deletes it. */
    private int failedRetentionHours = 72;
    private final Retry retry = new Retry();
    private final ClamAv clamav = new ClamAv();
    private final AzureDefender azureDefender = new AzureDefender();
    private final AwsGuardDuty awsGuardduty = new AwsGuardDuty();

    @Getter
    @Setter
    public static class Retry {
        private long pollIntervalMs = 30_000;
        private int maxAttempts = 10;
        private int initialBackoffSeconds = 30;
        private int maxBackoffSeconds = 900;
        /** Must comfortably exceed timeoutSeconds plus the staging -> document/quarantine copy time. */
        private int leaseSeconds = 300;
        private int batchSize = 20;
    }

    @Getter
    @Setter
    public static class ClamAv {
        private String host = "localhost";
        private int port = 3310;
        private int connectTimeoutMs = 5_000;
        private int chunkSizeBytes = 65_536;
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
