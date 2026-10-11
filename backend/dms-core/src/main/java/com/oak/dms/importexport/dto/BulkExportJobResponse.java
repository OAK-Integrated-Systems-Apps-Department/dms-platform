package com.oak.dms.importexport.dto;

import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import com.oak.dms.importexport.entity.ExportJob.MetadataFormat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One of the caller's bulk exports.
 *
 * @param documentCount     documents requested while pending/processing; once
 *                          finished, documents that went into the ZIP
 * @param skippedCount      requested documents left out, each listed with its reason in the ZIP
 * @param downloadAvailable completed and not yet expired
 */
public record BulkExportJobResponse(UUID exportJobId, ExportJobStatus status, MetadataFormat metadataFormat,
                                    int documentCount, int skippedCount, Long fileSizeBytes, String failureReason,
                                    OffsetDateTime createdAt, OffsetDateTime completedAt, OffsetDateTime expiresAt,
                                    boolean downloadAvailable) {

    public static BulkExportJobResponse from(ExportJob job, OffsetDateTime now) {
        boolean available = job.getStatus() == ExportJobStatus.completed
                && job.getExpiresAt() != null && job.getExpiresAt().isAfter(now);
        return new BulkExportJobResponse(job.getExportJobId(), job.getStatus(), job.getMetadataFormat(),
                job.getDocumentCount(), job.getSkippedCount(), job.getFileSizeBytes(), job.getFailureReason(),
                job.getCreatedAt(), job.getCompletedAt(), job.getExpiresAt(), available);
    }
}
