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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One queued export request. {@code requestParams} records what to export and
 * is read by the export worker; the generated file is kept at
 * {@code fileObjectKey} until {@code expiresAt}. There is no requested-by
 * column - {@code createdBy} records the requester.
 */
@Entity
@Table(name = "export_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExportJob {

    @Id
    @UuidGenerator
    @Column(name = "export_job_id", updatable = false, nullable = false)
    private UUID exportJobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "export_type", nullable = false)
    private ExportType exportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "metadata_format", nullable = false)
    private MetadataFormat metadataFormat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_params", nullable = false, columnDefinition = "jsonb")
    private String requestParams;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ExportJobStatus status = ExportJobStatus.pending;

    /** Bulk export: documents requested while pending/processing, documents in the ZIP once finished. */
    @Column(name = "document_count", nullable = false)
    @Builder.Default
    private int documentCount = 0;

    /** Requested documents left out of the ZIP (V54) - each listed with its reason inside it. */
    @Column(name = "skipped_count", nullable = false)
    @Builder.Default
    private int skippedCount = 0;

    @Column(name = "file_object_key")
    private String fileObjectKey;

    /** Where {@code fileObjectKey} lives (V55) - the tenant's staging location when the ZIP was written. */
    @Column(name = "file_storage_location_id")
    private UUID fileStorageLocationId;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "expiry_warning_sent_at")
    private OffsetDateTime expiryWarningSentAt;

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

    public enum ExportType {
        single_document, bulk_selection, bulk_folder, report_data
    }

    public enum MetadataFormat {
        xlsx, csv
    }

    public enum ExportJobStatus {
        pending, processing, completed, failed, expired
    }
}
