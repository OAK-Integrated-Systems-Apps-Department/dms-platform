package com.oak.dms.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code dms.notification.*} (3.2). The shared notification service
 * configuration: the From address, and the dispatcher (SKIP LOCKED claim,
 * lease, backoff, retries, per-instance on/off switch).
 */
@Component
@ConfigurationProperties(prefix = "dms.notification")
@Getter
@Setter
public class NotificationProperties {

    /** The single From address for all service mail (migrated mailers used 3 fragmented keys). */
    private String mailFrom = "no-reply@oakdms.local";

    private Dispatcher dispatcher = new Dispatcher();

    @Getter
    @Setter
    public static class Dispatcher {
        /**
         * Per-instance switch (decision 3): true runs the delivery poller on this
         * instance. Turn it off to move delivery to a dedicated instance or the
         * notification-worker later without changing any caller. Off in tests,
         * which drive the dispatcher directly.
         */
        private boolean enabled = true;
        /** How often the poller scans for due deliveries. */
        private java.time.Duration pollInterval = java.time.Duration.ofSeconds(30);
        /** How long a claimed row stays leased before another instance may reclaim it. */
        private java.time.Duration lease = java.time.Duration.ofMinutes(2);
        /** Rows claimed per scan. */
        private int batchSize = 50;
        /** First retry delay; doubles each attempt (exponential backoff). */
        private java.time.Duration baseBackoff = java.time.Duration.ofSeconds(60);
        /** Cap on the backoff delay. */
        private java.time.Duration maxBackoff = java.time.Duration.ofMinutes(30);
        /** Attempts before a delivery is marked failed (N-NFR-001). */
        private int maxAttempts = 5;
    }
}
