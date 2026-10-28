package com.oak.dms.importexport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One bulk import or migration run against a single document type, pinned to
 * the {@code schemaVersion} that was current when the batch was created. Each
 * manifest row is an {@link ImportItem}; the counters here summarize them.
 */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportBatch {

    @Id
    @UuidGenerator
    @Column(name = "import_batch_id", updatable = false, nullable = false)
    private UUID importBatchId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ImportBatchStatus status = ImportBatchStatus.uploading;

    @Column(name = "total_records", nullable = false)
    @Builder.Default
    private int totalRecords = 0;

    @Column(name = "success_count", nullable = false)
    @Builder.Default
    private int successCount = 0;

    @Column(name = "failure_count", nullable = false)
    @Builder.Default
    private int failureCount = 0;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    /** The saved import template the batch was uploaded with (V50); null for the standard template. */
    @Column(name = "import_template_id", updatable = false)
    private UUID importTemplateId;

    /** The folder the batch's documents are filed in (V51); null = not in a folder. */
    @Column(name = "folder_id", updatable = false)
    private UUID folderId;

    /**
     * What to do with a row whose file is already in the DMS (V65): keep_both (no check),
     * new_version (add it to the existing document) or skip.
     */
    @Column(name = "duplicate_action", nullable = false, updatable = false)
    @Builder.Default
    private String duplicateAction = "keep_both";

    /**
     * "Run OCR on imported files" (V84): a JSON array of field_keys to extract
     * from every file in this batch, or null/{@code []} for no OCR.
     * <p>
     * Raw JSON rather than a {@code List<String>}: it is handed to ocr-worker
     * verbatim as the message's {@code requested_fields}, and holding it as
     * the same text the queue carries is what makes that round trip lossless.
     * Same convention as {@code ScanResult#ocrRequestedFields}.
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "ocr_requested_fields", updatable = false)
    private String ocrRequestedFields;

    /**
     * How many of this batch's files have been queued for OCR (V84).
     * <p>
     * Durable rather than in-memory because it enforces
     * {@code dms.ocr.max-per-import-batch}: a worker that restarts half way
     * through a 5,000-row import, or a redelivered batch message, must not
     * get a fresh allowance.
     */
    @Column(name = "ocr_queued_count", nullable = false)
    @Builder.Default
    private int ocrQueuedCount = 0;

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

    public enum SourceType {
        bulk_import, migration
    }

    public enum ImportBatchStatus {
        uploading, queued, running, completed, completed_with_errors, failed, cancelled
    }
}
