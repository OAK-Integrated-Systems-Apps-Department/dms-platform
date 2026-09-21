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
 * One versioned row of a {@link DocumentType}'s field mapping (MDTM-FR-005).
 * A publish (see {@code DocumentTypeFieldServiceImpl#replaceMapping}) never
 * mutates an existing row in place: it archives the prior version's rows
 * ({@code status = archived}, {@code archivedAt} set) and inserts a fresh set
 * at {@code schemaVersion + 1}, so a historical version stays queryable
 * exactly as it was published.
 */
@Entity
@Table(name = "document_type_fields", uniqueConstraints = {
        @UniqueConstraint(name = "uq_document_type_fields_type_field_version",
                columnNames = {"document_type_id", "field_id", "schema_version"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentTypeField {

    @Id
    @UuidGenerator
    @Column(name = "document_type_field_id", updatable = false, nullable = false)
    private UUID documentTypeFieldId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "field_id", nullable = false)
    private UUID fieldId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private MappingStatus status = MappingStatus.active;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private boolean mandatory = false;

    @Column(name = "is_hidden", nullable = false)
    @Builder.Default
    private boolean hidden = false;

    /**
     * Set true when a publish makes this field newly mandatory (MDTM
     * workflow step 7 / alternate flow 3), so legacy documents can be
     * flagged for optional backfill rather than rejected retroactively.
     */
    @Column(name = "requires_backfill", nullable = false)
    @Builder.Default
    private boolean requiresBackfill = false;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /**
     * Read-only mirror of the generated column backing
     * {@code uq_dtf_type_version_order} (V26) - collapses to null for
     * soft-deleted rows so they never collide with a live row's
     * display_order. Never set by application code.
     */
    @Column(name = "display_order_uniq_key", insertable = false, updatable = false)
    private Integer displayOrderUniqKey;

    @Column(name = "field_group")
    private String fieldGroup;

    @Column(name = "default_value_override")
    private String defaultValueOverride;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

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

    public enum MappingStatus {
        active, archived
    }
}
