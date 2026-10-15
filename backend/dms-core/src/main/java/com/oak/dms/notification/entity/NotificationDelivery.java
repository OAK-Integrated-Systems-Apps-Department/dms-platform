package com.oak.dms.notification.entity;

import com.oak.dms.notification.enums.DeliveryStatus;
import com.oak.dms.notification.enums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One channel delivery of a notification (3.2). The dispatcher claims
 * {@code pending} rows with FOR UPDATE SKIP LOCKED plus a {@code lockedUntil}
 * lease, sends, and retries with backoff up to {@code maxAttempts}. In-app
 * rows are created already {@code sent} (the inbox row IS the delivery).
 */
@Entity
@Table(name = "notification_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDelivery {

    @Id
    @UuidGenerator
    @Column(name = "delivery_id", updatable = false, nullable = false)
    private UUID deliveryId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private NotificationChannel channel;

    @Column(name = "destination")
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.pending;

    @Column(name = "skip_reason")
    private String skipReason;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private int maxAttempts = 5;

    @Column(name = "next_attempt_at", nullable = false)
    @Builder.Default
    private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
