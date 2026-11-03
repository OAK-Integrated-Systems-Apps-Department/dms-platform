package com.oak.dms.editing;

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

/**
 * One tenant's online-editing policy (V128). No row means the platform default: off, exclusive - see
 * {@link EditingSettingsService}.
 */
@Entity
@Table(name = "tenant_editing_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantEditingSettings {

    @Id
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** {@code exclusive} or {@code simultaneous}; the database CHECK refuses anything else. */
    @Column(name = "mode", nullable = false)
    private String mode;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
