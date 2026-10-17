package com.oak.dms.document;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only mirror of {@code document_ocr_line_items} (V39) -- ocr-worker
 * owns writing it, same arrangement as {@link DocumentOcrFieldResult}.
 * dms-core reads it to render the line-item grid on the review screen.
 * <p>
 * Read-only on the screen too, in this phase: a reviewer can see what was
 * read off each row and which page it came from, but correcting a line is
 * not the same problem as correcting a field (a line has four values and
 * a position, and "wrong" can mean a split or merged row rather than a
 * bad number). Editing them is deliberately out of scope until there is a
 * real corpus of what reviewers actually need to fix.
 */
@Entity
@Table(name = "document_ocr_line_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentOcrLineItem {

    @Id
    @Column(name = "line_item_id", updatable = false, nullable = false)
    private UUID lineItemId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    /** 0-based position down the page; ordering is part of the data. */
    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "description")
    private String description;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "line_total")
    private BigDecimal lineTotal;

    /** The cells as OCR read them, before parsing to NUMERIC. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_cells", columnDefinition = "jsonb")
    private String rawCells;

    @Column(name = "confidence")
    private BigDecimal confidence;

    @Column(name = "source_page")
    private Integer sourcePage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_bbox", columnDefinition = "jsonb")
    private String sourceBbox;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
