package com.oak.dms.metadata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A content type (e.g. Contract, Invoice, Policy) with its own versioned set
 * of mandatory/optional metadata fields (MDTM-FR-002), mapped via
 * {@link DocumentTypeSchema}.
 */
@Entity
@Table(name = "document_types", uniqueConstraints = {
        @UniqueConstraint(name = "uq_document_types_tenant_document_type_id", columnNames = {"tenant_id", "document_type_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentType {

    @Id
    @UuidGenerator
    @Column(name = "document_type_id", updatable = false, nullable = false)
    private UUID documentTypeId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "type_code", nullable = false)
    private String typeCode;

    @Column(name = "type_name", nullable = false)
    private String typeName;

    @Column(name = "description")
    private String description;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /**
     * Read-only mirror of the generated column backing
     * {@code uq_dt_tenant_order} (V26) - collapses to null for soft-deleted
     * rows so they never collide with a live row's display_order. Never set
     * by application code.
     */
    @Column(name = "display_order_uniq_key", insertable = false, updatable = false)
    private Integer displayOrderUniqKey;

    @Column(name = "current_schema_version", nullable = false)
    @Builder.Default
    private int currentSchemaVersion = 1;

    /**
     * Plain UUID, no FK - module 6.1 (Records Retention & Compliance
     * Management) doesn't exist yet. Becomes a real reference to
     * retention_policies once that module lands.
     */
    @Column(name = "default_retention_policy_id")
    private UUID defaultRetentionPolicyId;

    /**
     * Whether documents of this type are meant to carry a watermark.
     * <p>
     * <b>Not enforced yet.</b> It is stored and returned by the API, and
     * nothing reads it to decide anything: a user can watermark a document
     * whose type says false, and can download one whose type says true
     * without a mark. Phase 1 watermarking is entirely on request - see
     * docs/watermarking.md. This is a Phase 2 seam, left untouched in
     * behaviour on purpose so that turning enforcement on later is a change
     * in one place rather than an archaeology exercise.
     */
    @Column(name = "requires_watermark", nullable = false)
    @Builder.Default
    private boolean requiresWatermark = false;

    @Column(name = "coediting_enabled", nullable = false)
    @Builder.Default
    private boolean coeditingEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private DocumentTypeStatus status = DocumentTypeStatus.active;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public enum DocumentTypeStatus {
        active, inactive
    }
}
