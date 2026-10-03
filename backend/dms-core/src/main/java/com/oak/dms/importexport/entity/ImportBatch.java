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

    /**
     * The finished import this batch resubmits the failed rows of (from its
     * failed-rows template); null for a normal import.
     */
    @Column(name = "resubmit_of_batch_id")
    private UUID resubmitOfBatchId;

    /**
     * Who cancelled the batch; null unless it is {@code cancelled} - and for
     * batches cancelled before this was recorded, which were all cancelled
     * by their user.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    /**
     * How many times {@code ImportBatchRecoveryJob} has acted on this batch
     * after it stalled while queued or running.
     */
    @Column(name = "recovery_attempts", nullable = false)
    @Builder.Default
    private int recoveryAttempts = 0;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

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

    /** {@code user}: the cancel endpoint; {@code expired}: no uploads for too long (the recovery job). */
    public enum CancelReason {
        user, expired
    }
}
