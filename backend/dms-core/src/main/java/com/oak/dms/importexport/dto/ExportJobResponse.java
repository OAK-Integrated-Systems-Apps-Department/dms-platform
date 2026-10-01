package com.oak.dms.importexport.dto;

import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.importexport.enums.ExportType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of "My exports". Never carries file_object_key: the storage path
 * is internal, and downloads will go through their own endpoint (Phase 4) -
 * the client only needs to know whether one is available.
 */
public record ExportJobResponse(
        UUID exportJobId,
        ExportType exportType,
        ExportJobStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime expiresAt,
        boolean downloadAvailable
) {
    public static ExportJobResponse from(ExportJob job) {
        return new ExportJobResponse(
                job.getExportJobId(),
                job.getExportType(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getExpiresAt(),
                job.getStatus() == ExportJobStatus.completed && job.getFileObjectKey() != null);
    }
}
