package com.oak.dms.metadata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * All metadata values of one document, validated against one published
 * {@link DocumentTypeSchema} version ({@code documentTypeId},
 * {@code schemaVersion}). Values live in {@link #fieldValues}, keyed by the
 * metadata field's id:
 * <pre>{"&lt;fieldId&gt;": {"v": ..., "src": "manual", "conf": 0.97}}</pre>
 * Nothing on this branch writes values yet (module 2.5 / A11 will); single
 * fields should be changed with
 * {@code DocumentMetadataRepository#setFieldValue}/{@code #clearFieldValue},
 * which update the JSONB in place instead of rewriting the whole map.
 */
@Entity
@Table(name = "document_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentMetadata {

    @EmbeddedId
    private DocumentMetadataId id;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_values", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, FieldValue> fieldValues = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "backfill_status", nullable = false)
    @Builder.Default
    private BackfillStatus backfillStatus = BackfillStatus.none;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public enum BackfillStatus {
        none, pending, completed
    }

    /**
     * One field's value.
     *
     * @param v    the value: a string, an exact number ({@link BigDecimal}
     *             for decimals - see {@code HibernateJsonFormatMapperConfig}),
     *             a boolean, an ISO-8601 string for dates, or a list for
     *             multi-select fields
     * @param src  manual | ocr | import | migration | integration
     * @param conf extraction confidence (0..1) for OCR-sourced values, else null
     */
    public record FieldValue(Object v, String src, BigDecimal conf) {
    }
}
