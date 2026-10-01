package com.oak.dms.importexport.entity;

import com.oak.dms.importexport.enums.MigrationBatchStatus;
import com.oak.dms.importexport.enums.TemplateFormat;
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
 * One template-based bulk import: an uploaded template plus its files
 * (Table Dictionary 11.4 - see V42 for the added columns). Its rows are
 * {@link MigrationItem}s; the counts here are kept in step with them by
 * {@link com.oak.dms.importexport.service.BulkImportRowProcessor}.
 */
@Entity
@Table(name = "migration_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationBatch {

    /** source_system of batches created from an import template. */
    public static final String SOURCE_TEMPLATE = "bulk_import_template";

    @Id
    @UuidGenerator
    @Column(name = "migration_batch_id", updatable = false, nullable = false)
    private UUID migrationBatchId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "source_system", nullable = false, updatable = false)
    @Builder.Default
    private String sourceSystem = SOURCE_TEMPLATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private MigrationBatchStatus status = MigrationBatchStatus.pending;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "document_type_id", nullable = false, updatable = false)
    private UUID documentTypeId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "template_format", nullable = false, updatable = false)
    private TemplateFormat templateFormat;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
