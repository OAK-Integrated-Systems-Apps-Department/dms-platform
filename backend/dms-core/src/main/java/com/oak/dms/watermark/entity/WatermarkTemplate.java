package com.oak.dms.watermark.entity;

import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Named watermark text and style (V68). A row with {@code tenantId} null is
 * one of the four system templates seeded by V68 - visible to every tenant,
 * editable by none, the same "NULL tenant means platform default"
 * convention storage_locations and scan_engines already use. Everything else
 * belongs to exactly one tenant and is managed by that tenant's admins
 * through {@link com.oak.dms.watermark.service.WatermarkTemplateService}.
 * <p>
 * Every style column is nullable, and null means "whatever the system
 * default is" rather than "no style": a template that only wants bigger text
 * sets {@code fontSize} and leaves the rest alone. The full precedence is
 * request &gt; template &gt; {@code dms.watermark.*}.
 * <p>
 * {@link #version} is a JPA optimistic-locking version, which gives the
 * feature two things for one column: two admins editing the same template
 * can't silently overwrite each other, and the number is stable enough to
 * snapshot into {@code watermark_jobs.template_version}, so a finished job
 * always records which wording it was actually produced from.
 */
@Entity
@Table(name = "watermark_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatermarkTemplate {

    @Id
    @UuidGenerator
    @Column(name = "template_id", updatable = false, nullable = false)
    private UUID templateId;

    /** Null for a system template - see the class comment. Never changes: a template can't move tenant. */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "default_text", nullable = false)
    private String defaultText;

    @Column(name = "font_size")
    private Integer fontSize;

    @Column(name = "opacity")
    private Integer opacity;

    @Column(name = "rotation")
    private Integer rotation;

    /** {@code #RRGGBB}, or null for the system default. */
    @Column(name = "color")
    private String color;

    @Column(name = "placement")
    private WatermarkPlacement placement;

    @Column(name = "page_scope")
    private WatermarkPageScope pageScope;

    @Column(name = "is_system_default", nullable = false)
    private boolean systemDefault;

    /**
     * An inactive template is kept and still names the jobs already made
     * from it, but can't be chosen for a new one. Deactivating is how an
     * admin retires a template without breaking its history.
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private int version = 0;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
