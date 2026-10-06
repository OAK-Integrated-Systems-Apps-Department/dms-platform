package com.oak.dms.watermark.dto;

import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One watermark job as the API returns it - mirroring
 * {@code ExportJobResponse}.
 * <p>
 * Never carries {@code outputObjectKey} or the storage location. The file is
 * only ever fetched through {@code GET /api/v1/watermark-jobs/{id}/result},
 * which re-checks access, and the client only needs to know whether it can
 * be.
 *
 * @param downloadAvailable completed, not expired, and the output still
 *                          there - computed here so a client doesn't have to
 *                          reimplement the rule and get it subtly wrong
 * @param failureReason     written for the person who asked. Never a stack
 *                          trace, a storage key or an internal path.
 */
public record WatermarkJobResponse(
        UUID jobId,
        UUID documentId,
        UUID versionId,
        UUID templateId,
        Integer templateVersion,
        WatermarkJobStatus status,
        String text,
        boolean downloadAvailable,
        Long outputSizeBytes,
        OffsetDateTime expiresAt,
        String failureCode,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {

    /**
     * @param text the resolved watermark text. Shown back deliberately: the
     *             person is about to put this on a document and send it
     *             somewhere, and it carries their own name.
     */
    public static WatermarkJobResponse from(WatermarkJob job, String text) {
        boolean available = job.getStatus() == WatermarkJobStatus.completed
                && job.getOutputObjectKey() != null
                && job.getPurgedAt() == null
                && (job.getExpiresAt() == null || job.getExpiresAt().isAfter(OffsetDateTime.now()));

        return new WatermarkJobResponse(
                job.getJobId(),
                job.getDocumentId(),
                job.getVersionId(),
                job.getTemplateId(),
                job.getTemplateVersion(),
                job.getStatus(),
                text,
                available,
                job.getOutputSizeBytes(),
                job.getExpiresAt(),
                job.getFailureCode(),
                job.getFailureReason(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt());
    }
}
