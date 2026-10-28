package com.oak.dms.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One reviewer decision about one field (V43), with what OCR had said
 * alongside it.
 * <p>
 * Append-only. A reviewer who corrects a field, looks again and corrects
 * it differently has produced two observations, and the value they
 * rejected is worth keeping -- so nothing here is ever updated, and "the
 * current decision" is the newest row.
 * <p>
 * Acceptances are recorded as well as corrections, because a log of only
 * corrections has no denominator: forty corrections means nothing without
 * knowing whether fifty fields were reviewed or fifty thousand.
 */
@Entity
@Table(name = "document_ocr_corrections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentOcrCorrection {

    /** The reviewer confirmed OCR was right. */
    public static final String ACTION_ACCEPTED = "accepted";
    /** The reviewer replaced OCR's value. */
    public static final String ACTION_CORRECTED = "corrected";

    /** They typed the value themselves. */
    public static final String SOURCE_TYPED = "typed";
    /**
     * They drew a box and OCR read it. A different fact from
     * {@link #SOURCE_TYPED}: it says OCR could read the text once pointed
     * at the right part of the page, which is a localisation failure, not
     * a recognition one.
     */
    public static final String SOURCE_REGION = "region";

    @Id
    @GeneratedValue
    @Column(name = "correction_id", updatable = false, nullable = false)
    private UUID correctionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "field_key", nullable = false)
    private String fieldKey;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "source")
    private String source;

    @Column(name = "original_value")
    private String originalValue;

    /** Kept even for an acceptance -- "accepted at 0.62" is the observation
     * that says the low-confidence threshold is set too low. */
    @Column(name = "original_confidence")
    private BigDecimal originalConfidence;

    @Column(name = "original_status")
    private String originalStatus;

    /** Equal to {@link #originalValue} for an acceptance, written out rather
     * than left null so "the true value of this field" never branches. */
    @Column(name = "corrected_value")
    private String correctedValue;

    @Column(name = "corrected_by")
    private UUID correctedBy;

    @Column(name = "corrected_at", nullable = false)
    @Builder.Default
    private OffsetDateTime correctedAt = OffsetDateTime.now();
}
