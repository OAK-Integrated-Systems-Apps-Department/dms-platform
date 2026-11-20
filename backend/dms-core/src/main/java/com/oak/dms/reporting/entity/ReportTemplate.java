package com.oak.dms.reporting.entity;

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
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A named, saved report definition (V138 {@code report_templates}). {@code definition} is the JSON of a
 * {@code ReportDefinition} at {@code definitionVersion}; {@code TemplateDefinitions} migrates old versions when it is read.
 * An official template has no owner - it belongs to the organisation, so it outlives whoever published it.
 */
@Entity
@Table(name = "report_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportTemplate {

    public static final String PRIVATE = "private";
    public static final String SHARED = "shared";
    public static final String OFFICIAL = "official";

    @Id
    @Column(name = "template_id", updatable = false, nullable = false)
    @Builder.Default
    private UUID templateId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Null for an official template. */
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "scope", nullable = false)
    private String scope;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> definition;

    @Column(name = "definition_version", nullable = false)
    private int definitionVersion;

    /** Official only: people may run it but not change its columns, grouping or format. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    /** Official and locked only: the filters (catalogue keys) a person running it may still change. */
    @Column(name = "user_editable_filters", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> userEditableFilters = new ArrayList<>();

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public boolean isOfficial() {
        return OFFICIAL.equals(scope);
    }
}
