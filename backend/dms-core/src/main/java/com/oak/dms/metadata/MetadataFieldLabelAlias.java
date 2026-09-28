package com.oak.dms.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One OCR label hint of a {@link MetadataField}: a word or phrase the field
 * may appear as on a scanned document ("Inv #", "PO Number"), which
 * ocr-worker's label matcher searches for when extracting that field (see
 * V40 for the full design rationale). ocr-worker reads this table directly;
 * dms-core owns every write, via {@link MetadataFieldLabelAliasService}.
 * <p>
 * {@link #documentTypeId} null = a global hint, applied wherever the field
 * is mapped; non-null = scoped to documents of that one type, tried before
 * the global ones. Deliberately no soft-delete columns: a (field, scope)
 * list is always replaced wholesale (hard delete + reinsert), and the rows
 * cascade away with their field or document type.
 */
@Entity
@Table(name = "metadata_field_label_aliases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetadataFieldLabelAlias {

    @Id
    @UuidGenerator
    @Column(name = "label_alias_id", updatable = false, nullable = false)
    private UUID labelAliasId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "field_id", nullable = false)
    private UUID fieldId;

    @Column(name = "document_type_id")
    private UUID documentTypeId;

    @Column(name = "alias_text", nullable = false)
    private String aliasText;

    /** Match priority within (field, scope) - lower is tried first; stored as (index + 1) * 10. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
