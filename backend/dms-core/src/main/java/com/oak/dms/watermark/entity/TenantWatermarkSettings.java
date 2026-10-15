package com.oak.dms.watermark.entity;

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
 * One tenant's policy for the plain "Download" action (V103): whether it
 * should quietly watermark the file inline, synchronously, before streaming
 * it, and which template to use.
 * <p>
 * No row for a tenant means the platform default, which is off - the same
 * "no row = default" convention {@code pin_settings} (V101) uses. This is
 * kept deliberately narrow (one feature, one row per tenant) rather than a
 * general tenant-settings bag; a second tenant-level toggle some day is free
 * to get its own table the same way.
 * <p>
 * This is NOT the async "Add watermark" job pipeline
 * ({@code watermark_jobs}/{@code WatermarkJobWorker}) - that feature is
 * unaffected by this table and this entity is never read by it.
 */
@Entity
@Table(name = "tenant_watermark_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantWatermarkSettings {

    @Id
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "download_watermark_enabled", nullable = false)
    private boolean downloadWatermarkEnabled;

    /**
     * Which template a plain download is stamped with when
     * {@link #downloadWatermarkEnabled} is true. Null is only valid when the
     * toggle is off - {@code WatermarkDefaultsService} enforces that on
     * every write, so a row here is never "on with nothing to draw".
     */
    @Column(name = "default_template_id")
    private UUID defaultTemplateId;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
