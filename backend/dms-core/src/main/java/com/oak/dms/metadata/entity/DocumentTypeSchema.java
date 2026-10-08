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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One published version of a {@link DocumentType}'s field mapping
 * (MDTM-FR-005): the whole ordered mapping for that version lives in
 * {@link #fields} as a JSONB array, one row per (document type, version).
 * A publish (see {@code DocumentTypeFieldServiceImpl#replaceMapping}) never
 * edits a published row's fields: it archives the previous version's row
 * ({@code status = archived}, {@code archivedAt} set) and inserts a new row
 * at {@code schemaVersion + 1}, so every historical version stays queryable
 * exactly as it was published.
 */
@Entity
@Table(name = "document_type_schemas", uniqueConstraints = {
        @UniqueConstraint(name = "uq_dts_type_version", columnNames = {"document_type_id", "schema_version"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentTypeSchema {

    @Id
    @UuidGenerator
    @Column(name = "schema_id", updatable = false, nullable = false)
    private UUID schemaId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "document_type_id", nullable = false, updatable = false)
    private UUID documentTypeId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private SchemaStatus status = SchemaStatus.active;

    /** Stored in displayOrder order; immutable once published. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fields", nullable = false, updatable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<FieldMapping> fields = new ArrayList<>();

    @Column(name = "published_by", updatable = false)
    private UUID publishedBy;

    @Column(name = "published_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime publishedAt = OffsetDateTime.now();

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public enum SchemaStatus {
        active, archived
    }

    /**
     * One field's placement in a published schema version. {@code mappingId}
     * is what the API exposes as {@code documentTypeFieldId} - for versions
     * backfilled by V36 it is the original document_type_fields row id.
     *
     * @param requiresBackfill set when this publish made the field newly
     *                         mandatory (MDTM workflow step 7 / alternate
     *                         flow 3), so legacy documents can be flagged
     *                         for optional backfill rather than rejected
     */
    public record FieldMapping(
            UUID mappingId,
            UUID fieldId,
            boolean mandatory,
            boolean hidden,
            boolean requiresBackfill,
            int displayOrder,
            String fieldGroup,
            String defaultValueOverride
    ) {
    }
}
