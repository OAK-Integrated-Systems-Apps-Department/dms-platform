package com.oak.dms.importexport.entity;

import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.importexport.enums.ExportType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One requested export (Module 2.1, Table Dictionary 11.6 - see V41, and
 * V48 for the columns the export worker needs). Produced by
 * {@link com.oak.dms.importexport.export.ExportJobWorker}: a ZIP of the
 * documents and a metadata sheet, stored at {@code fileObjectKey} in
 * {@code storageLocationId} until {@code expiresAt}. Create rows through
 * {@link com.oak.dms.importexport.service.ExportJobService}, which validates
 * the enum values before anything is written.
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

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "export_type", nullable = false, updatable = false)
    private ExportType exportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ExportJobStatus status = ExportJobStatus.pending;

    /** e.g. {tenant_id}/tmp/exports/{export_id}/export.zip - set once the export is generated. */
    @Column(name = "file_object_key")
    private String fileObjectKey;

    /** Auto-purge deadline; the TTL behind it is {@code dms.export.job-ttl}. */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "metadata_format", nullable = false, updatable = false)
    @Builder.Default
    private TemplateFormat metadataFormat = TemplateFormat.xlsx;

    /** What to export - an {@link com.oak.dms.importexport.dto.CreateExportRequest} as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_params", nullable = false, columnDefinition = "jsonb", updatable = false)
    @Builder.Default
    private String requestParams = "{}";

    @Column(name = "document_count", nullable = false)
    private int documentCount;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** Where {@link #fileObjectKey} is stored - never returned to clients. */
    @Column(name = "storage_location_id")
    private UUID storageLocationId;
}
