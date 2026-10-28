package com.oak.dms.importexport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A tenant's saved import template for one document type (V50): which
 * fields are columns, in what order and under which header, and default
 * values for fields left out or blank. Managed by
 * {@link com.oak.dms.importexport.service.ImportTemplateService}.
 */
@Entity
@Table(name = "import_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportTemplate {

    /**
     * One metadata column. {@code header} is what the column is called in
     * the downloaded file; null means the field's own label.
     */
    public record Column(String fieldKey, String header) {
    }

    @Id
    @UuidGenerator
    @jakarta.persistence.Column(name = "import_template_id", updatable = false, nullable = false)
    private UUID importTemplateId;

    @jakarta.persistence.Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @jakarta.persistence.Column(name = "document_type_id", nullable = false, updatable = false)
    private UUID documentTypeId;

    @jakarta.persistence.Column(name = "name", nullable = false)
    private String name;

    @jakarta.persistence.Column(name = "description")
    private String description;

    /** Metadata columns in order; filename and title always come first and aren't listed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @jakarta.persistence.Column(name = "columns", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Column> columns = new ArrayList<>();

    /** field_key -> raw default cell text. */
    @JdbcTypeCode(SqlTypes.JSON)
    @jakarta.persistence.Column(name = "defaults", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, String> defaults = new LinkedHashMap<>();

    @jakarta.persistence.Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @jakarta.persistence.Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @jakarta.persistence.Column(name = "updated_by")
    private UUID updatedBy;

    @jakarta.persistence.Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @jakarta.persistence.Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @jakarta.persistence.Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
