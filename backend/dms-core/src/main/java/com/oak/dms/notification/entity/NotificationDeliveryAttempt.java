package com.oak.dms.notification.entity;

import com.oak.dms.notification.enums.DeliveryOutcome;
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
 * One append-only attempt record (3.2, N-FR-005 / N-NFR-005). The table blocks
 * UPDATE/DELETE with a trigger + REVOKE (V82), and this entity is never
 * updated or deleted - its repository exposes no mutators. Included in the
 * compliance export.
 */
@Entity
@Table(name = "notification_delivery_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDeliveryAttempt {

    @Id
    @UuidGenerator
    @Column(name = "attempt_id", updatable = false, nullable = false)
    private UUID attemptId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private NotificationChannel channel;

    @Column(name = "destination", updatable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private DeliveryOutcome outcome;

    @Column(name = "error_code", updatable = false)
    private String errorCode;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    @Column(name = "duration_ms", updatable = false)
    private Integer durationMs;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime attemptedAt = OffsetDateTime.now();
}
