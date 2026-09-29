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
 * Read-only mirror of {@code document_ocr_field_results} (V35) -- ocr-worker
 * owns writing this table directly (same direct-Postgres-access pattern it
 * already uses for {@code document_versions.ocr_status}/{@code ocr_outputs};
 * see backend/workers/ocr-worker/README.md "Database access"). dms-core only
 * reads it, to serve {@code GET /documents/{id}/ocr-status}. One row per
 * (document_id, field_key) -- a re-run replaces the row in place, so this is
 * always "OCR's latest attempt at this field", not a history.
 */
@Entity
@Table(name = "document_ocr_field_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentOcrFieldResult {

    @Id
    @Column(name = "field_result_id", updatable = false, nullable = false)
    private UUID fieldResultId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "field_key", nullable = false)
    private String fieldKey;

    @Column(name = "value")
    private String value;

    /**
     * The string as ocr-worker read it off the page, before normalisation (V39).
     * Equal to {@link #value} for every field that is not normalised; differs for
     * dates, where {@code value} is ISO and this is what the document printed.
     * <p>
     * Deliberately NOT consumed by {@link OcrFieldResultMapper}: a metadata value
     * must be the normalised one, or a {@code date} field would receive
     * "10/08/2026" and fail to parse. This exists for the Phase 4 review screen,
     * where showing a reviewer the ISO date alone gives them no way to judge a
     * day-first/month-first reading.
     * <p>
     * NULL on rows written before Phase 3.
     */
    @Column(name = "raw_value")
    private String rawValue;

    @Column(name = "confidence")
    private BigDecimal confidence;

    /** 'extracted' | 'not_found' | 'low_confidence' -- see V35's CHECK constraint. */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "source_page")
    private Integer sourcePage;

    /** JSON array [x0, y0, x1, y1], or null -- see V35's own comment on this table. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_bbox", columnDefinition = "jsonb")
    private String sourceBbox;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
