package com.oak.dms.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One value of one metadata field on one document -- module 2.7's own
 * migration (V28) created this table for a later module to write into
 * ("this module writes nothing into it - it exists for module 2.5/A11 to
 * use later"). This is that first writer.
 * <p>
 * Deliberately unconstrained by a real FK to {@code documents} (per V28's
 * own comment) -- {@link DocumentServiceImpl} validates the document
 * belongs to the caller's tenant before writing, same as everywhere else
 * in this package.
 * <p>
 * Exactly one {@code value*} column is populated per row, per the table's
 * own CHECK constraint; which one depends on the field's {@code data_type}
 * (see {@link com.oak.dms.metadata.MetadataField#DATA_TYPES}). {@code
 * user_ref}/{@code lookup} fields are stored in {@code valueText} rather
 * than {@code valueRefId} for now -- there is no picker UI yet to resolve
 * a real reference, so the raw text the caller submitted is kept as-is
 * instead of guessing at a UUID.
 * <p>
 * {@code valueOrdinal} makes a {@code multi_select} field possible: one row
 * per selected value, ordered by ordinal; every other data type has
 * exactly one row at ordinal 0.
 */
@Entity
@Table(name = "document_metadata_values", uniqueConstraints = {
        @UniqueConstraint(name = "uq_document_metadata_values_doc_field_ordinal",
                columnNames = {"document_id", "field_id", "value_ordinal"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentMetadataValue {

    @Id
    @UuidGenerator
    @Column(name = "metadata_value_id", updatable = false, nullable = false)
    private UUID metadataValueId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "field_id", nullable = false)
    private UUID fieldId;

    @Column(name = "value_ordinal", nullable = false)
    @Builder.Default
    private int valueOrdinal = 0;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "value_number")
    private BigDecimal valueNumber;

    @Column(name = "value_date")
    private OffsetDateTime valueDate;

    @Column(name = "value_boolean")
    private Boolean valueBoolean;

    @Column(name = "value_ref_id")
    private UUID valueRefId;

    /**
     * Plain String, not a Java enum: 'import' is a reserved word, same
     * reason {@link com.oak.dms.metadata.MetadataField#dataType} is a
     * plain String rather than an enum. One of SOURCES; the service layer
     * validates before this is set, matching the DB CHECK constraint.
     */
    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = "manual";

    @Column(name = "confidence_score")
    private BigDecimal confidenceScore;

    /**
     * True only for an OCR-sourced value whose extraction confidence fell
     * below ocr-worker's own threshold (status "low_confidence" -- see
     * extraction.py's RequestedFieldResult). Written, not suppressed: a
     * low-confidence guess is still saved (better than a blank field), just
     * flagged so the frontend can visually distinguish it from a confident
     * extraction rather than presenting both identically. Always false for
     * "manual" (a human editing/confirming a field is exactly what should
     * clear it) -- see DocumentMetadataServiceImpl#writeValues, which
     * deletes and rebuilds a field's rows on every write, so this never
     * needs an explicit clear step of its own.
     */
    @Column(name = "needs_review", nullable = false)
    @Builder.Default
    private boolean needsReview = false;

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

    public static final java.util.Set<String> SOURCES =
            java.util.Set.of("manual", "ocr", "import", "migration", "integration");
}
