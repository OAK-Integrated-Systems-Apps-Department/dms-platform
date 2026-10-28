package com.oak.dms.notification.entity;

import com.oak.dms.notification.enums.NotificationTier;
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
 * One inbox entry for one recipient (3.2). The durable record that satisfies
 * "not lost if offline" (WA-NFR-004): it stands even if email delivery fails.
 * {@code title}/{@code linkUrl} are already access-resolved for this recipient
 * (N9). {@code dedupeKey} makes a (tenant, recipient, dedupeKey) idempotent.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @UuidGenerator
    @Column(name = "notification_id", updatable = false, nullable = false)
    private UUID notificationId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "category_code", nullable = false, updatable = false)
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, updatable = false)
    private NotificationTier tier;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "link_url")
    private String linkUrl;

    @Column(name = "object_type", updatable = false)
    private String objectType;

    @Column(name = "object_id", updatable = false)
    private UUID objectId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "source_module", nullable = false, updatable = false)
    private String sourceModule;

    @Column(name = "dedupe_key", nullable = false, updatable = false)
    private String dedupeKey;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
