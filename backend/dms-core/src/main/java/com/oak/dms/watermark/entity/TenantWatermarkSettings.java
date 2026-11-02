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
 * One tenant's policy for watermarking a document on the way out, inline and
 * synchronously, rather than through the async "Add watermark" job pipeline
 * ({@code watermark_jobs}/{@code WatermarkJobWorker}, which is unaffected by
 * this table and never reads this entity): {@link #downloadWatermarkEnabled}
 * (V111) covers the plain "Download" action, and
 * {@link #bulkExportWatermarkEnabled} (V112) covers a bulk/ZIP export's files.
 * Both share the one {@link #defaultTemplateId} - there is only ever one
 * tenant-standard watermark, not a different one per download path.
 * <p>
 * No row for a tenant means the platform default, which is off for both - the
 * same "no row = default" convention {@code pin_settings} (V101) uses.
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

    @Column(name = "bulk_export_watermark_enabled", nullable = false)
    private boolean bulkExportWatermarkEnabled;

    /**
     * Which template is stamped on when either {@link #downloadWatermarkEnabled}
     * or {@link #bulkExportWatermarkEnabled} is true. Null is only valid when
     * both toggles are off - {@code WatermarkDefaultsService} enforces that on
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
