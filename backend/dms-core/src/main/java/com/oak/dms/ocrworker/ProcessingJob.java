package com.oak.dms.ocrworker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the existing {@code processing_jobs} table (V33, leasing columns
 * added by V36) -- ocr-worker owns claiming/leasing/completing rows (direct
 * Postgres access, same pattern as {@code document_ocr_field_results}); this
 * module only ever INSERTs a fresh {@code queued} row (Phase 1 B.1, upload/
 * rerun) and READs (getOcrStatus's error lookup, OcrEventsConsumer's
 * staleness check, prepareRerun's active-job guard). Never updates
 * {@code status}/{@code attempts}/lease fields -- those are ocr-worker-only.
 */
@Entity
@Table(name = "processing_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessingJob {

    /** {@code 'document_versions'} -- matches ocr-worker's repository.py exactly. */
    public static final String REFERENCE_TYPE_DOCUMENT_VERSION = "document_versions";

    /** {@code 'ocr'} -- one of processing_jobs.job_type's CHECK values (V33). */
    public static final String JOB_TYPE_OCR = "ocr";

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_DEAD_LETTER = "dead_letter";

    @Id
    @Column(name = "job_id", updatable = false, nullable = false)
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "reference_type", nullable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "correlation_id")
    private UUID correlationId;

    // @Builder.Default required -- without it Lombok's generated builder
    // silently drops this field initializer and leaves createdAt null,
    // violating the NOT NULL constraint (same pattern Document.createdAt
    // already uses).
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;
}
