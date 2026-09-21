package com.oak.dms.metadata.entity;

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
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single validation rule attached to a {@link MetadataField} (MDTM-FR-004).
 * Deliberately has no "required" rule type - mandatory-ness always comes from
 * {@link DocumentTypeField#isMandatory()}, never from a rule here.
 */
@Entity
@Table(name = "metadata_field_validations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetadataFieldValidation {

    @Id
    @UuidGenerator
    @Column(name = "validation_id", updatable = false, nullable = false)
    private UUID validationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "field_id", nullable = false)
    private UUID fieldId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private RuleType ruleType;

    @Column(name = "rule_value")
    private String ruleValue;

    @Column(name = "error_message")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    @Builder.Default
    private Severity severity = Severity.error;

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

    public enum RuleType {
        min_length, max_length, min_value, max_value, regex, date_format, date_range, allowed_values
    }

    public enum Severity {
        error, warning
    }
}
