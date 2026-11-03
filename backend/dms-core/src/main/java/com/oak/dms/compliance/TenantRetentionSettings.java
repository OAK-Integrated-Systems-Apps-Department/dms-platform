package com.oak.dms.compliance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One organisation's retention settings (V130). No row means the defaults - see {@link RetentionSettingsService}. */
@Entity
@Table(name = "tenant_retention_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantRetentionSettings {

    @Id
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "automation_enabled", nullable = false)
    @Builder.Default
    private boolean automationEnabled = true;

    @Column(name = "precedence", nullable = false)
    @Builder.Default
    private String precedence = RetentionResolver.LONGEST;

    @Column(name = "when_no_policy", nullable = false)
    @Builder.Default
    private String whenNoPolicy = RetentionSettingsService.FLAG;

    @Column(name = "require_status_reason", nullable = false)
    private boolean requireStatusReason;

    /** How long a version removed by retention stays hidden and restorable before its files are destroyed. */
    @Column(name = "version_grace_days", nullable = false)
    @Builder.Default
    private int versionGraceDays = RetentionSettingsService.DEFAULT_GRACE_DAYS;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
