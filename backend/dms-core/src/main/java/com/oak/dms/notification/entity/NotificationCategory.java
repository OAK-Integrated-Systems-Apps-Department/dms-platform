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

import java.time.OffsetDateTime;

/**
 * Global notification category catalogue (3.2), seeded by V82. The tier and
 * default decide email behaviour before any tenant/user preference applies.
 */
@Entity
@Table(name = "notification_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationCategory {

    @Id
    @Column(name = "category_code", updatable = false, nullable = false)
    private String categoryCode;

    @Column(name = "module", nullable = false)
    private String module;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false)
    private NotificationTier tier;

    @Column(name = "default_email_enabled", nullable = false)
    private boolean defaultEmailEnabled;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "description")
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
