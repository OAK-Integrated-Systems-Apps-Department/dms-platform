package com.oak.dms.sharing;

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

/** An organisation's external-sharing policy (tenant_sharing_settings, V144). No row = the defaults below, sharing off. */
@Entity
@Table(name = "tenant_sharing_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantSharingSettings {

    public static final String ON_RECLASSIFY_FLAG = "flag";
    public static final String ON_RECLASSIFY_REVOKE = "revoke";

    @Id
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "external_sharing_enabled", nullable = false)
    @Builder.Default
    private boolean externalSharingEnabled = false;

    @Column(name = "default_expiry_days", nullable = false)
    @Builder.Default
    private int defaultExpiryDays = 7;

    @Column(name = "max_expiry_days", nullable = false)
    @Builder.Default
    private int maxExpiryDays = 90;

    @Column(name = "max_password_attempts", nullable = false)
    @Builder.Default
    private int maxPasswordAttempts = 5;

    @Column(name = "lockout_minutes", nullable = false)
    @Builder.Default
    private int lockoutMinutes = 15;

    @Column(name = "guest_inactivity_days", nullable = false)
    @Builder.Default
    private int guestInactivityDays = 90;

    @Column(name = "on_reclassify", nullable = false)
    @Builder.Default
    private String onReclassify = ON_RECLASSIFY_FLAG;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    /** The defaults, for a tenant that never saved a policy. */
    public static TenantSharingSettings defaults(UUID tenantId) {
        return TenantSharingSettings.builder().tenantId(tenantId).build();
    }
}
