package com.oak.dms.metadata;

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
import java.util.Set;
import java.util.UUID;

/**
 * A single entry in the tenant's centralized metadata schema registry
 * (MDTM-FR-001) - the reusable catalog of typed fields that
 * {@link DocumentTypeField} draws from to build each document type's form.
 */
@Entity
@Table(name = "metadata_fields", uniqueConstraints = {
        @UniqueConstraint(name = "uq_metadata_fields_tenant_field_id", columnNames = {"tenant_id", "field_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetadataField {

    public static final Set<String> DATA_TYPES = Set.of(
            "text", "long_text", "number", "decimal", "date", "datetime",
            "boolean", "dropdown", "multi_select", "user_ref", "lookup");

    /** data_type values that resolve options via {@link MetadataFieldOption}. */
    public static final Set<String> OPTION_BACKED_DATA_TYPES = Set.of("dropdown", "multi_select");

    @Id
    @UuidGenerator
    @Column(name = "field_id", updatable = false, nullable = false)
    private UUID fieldId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "field_key", nullable = false)
    private String fieldKey;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "help_text")
    private String helpText;

    /**
     * Plain String, not a Java enum: 'boolean' (a valid data_type value and
     * the exact DB CHECK literal) is a reserved word, so it can't be an enum
     * constant name without a custom AttributeConverter. {@link #DATA_TYPES}
     * is the same allow-list the DB CHECK constraint enforces; requests are
     * validated against it in the service layer before this is set.
     */
    @Column(name = "data_type", nullable = false)
    private String dataType;

    @Column(name = "default_value")
    private String defaultValue;

    @Column(name = "is_system_field", nullable = false)
    @Builder.Default
    private boolean systemField = false;

    @Column(name = "is_searchable", nullable = false)
    @Builder.Default
    private boolean searchable = true;

    @Column(name = "is_ocr_extractable", nullable = false)
    @Builder.Default
    private boolean ocrExtractable = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private FieldStatus status = FieldStatus.active;

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

    public enum FieldStatus {
        active, inactive, deprecated
    }
}
