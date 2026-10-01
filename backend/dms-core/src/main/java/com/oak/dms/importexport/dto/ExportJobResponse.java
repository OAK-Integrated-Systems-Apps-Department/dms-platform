package com.oak.dms.importexport.dto;

import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.importexport.enums.ExportType;
import com.oak.dms.importexport.enums.TemplateFormat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of "My exports". Never carries file_object_key or the storage
 * location: the file is only ever fetched through
 * {@code GET /api/v1/export-jobs/{id}/download}, and the client only needs
 * to know whether it can be.
 */
public record ExportJobResponse(
        UUID exportJobId,
        ExportType exportType,
        ExportJobStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime expiresAt,
        boolean downloadAvailable,
        TemplateFormat metadataFormat,
        int documentCount,
        Long fileSizeBytes,
        String failureReason,
        OffsetDateTime completedAt
) {
    public static ExportJobResponse from(ExportJob job) {
        boolean available = job.getStatus() == ExportJobStatus.completed && job.getFileObjectKey() != null
                && (job.getExpiresAt() == null || job.getExpiresAt().isAfter(OffsetDateTime.now()));
        return new ExportJobResponse(
                job.getExportJobId(),
                job.getExportType(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getExpiresAt(),
                available,
                job.getMetadataFormat(),
                job.getDocumentCount(),
                job.getFileSizeBytes(),
                job.getFailureReason(),
                job.getCompletedAt());
    }
}
