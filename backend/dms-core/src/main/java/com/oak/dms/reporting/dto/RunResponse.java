package com.oak.dms.reporting.dto;

import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.run.ReportRunStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A run as the person who asked for it sees it. No object key, no storage detail, no SQL: the error is the sanitised message.
 *
 * @param downloadable completed, has a file, and the file has not expired
 * @param progressRows rows written so far while it runs
 */
public record RunResponse(UUID runId, ReportRunStatus status, String format, String trigger, UUID templateId, String title,
                          Long rowCount, boolean truncated, long progressRows, String errorCode, String errorMessage,
                          OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                          OffsetDateTime expiresAt, boolean downloadable) {

    public static RunResponse from(ReportRun r) {
        boolean downloadable = r.getStatus() == ReportRunStatus.completed && r.getFileObjectKey() != null
                && (r.getExpiresAt() == null || r.getExpiresAt().isAfter(OffsetDateTime.now()));
        String title = null;
        if (r.getDefinitionSnapshot() != null && r.getDefinitionSnapshot().get("title") instanceof String t) {
            title = t;
        }
        return new RunResponse(r.getRunId(), r.getStatus(), r.getFormat(), r.getTriggerType(), r.getTemplateId(), title,
                r.getRowCount(), r.isTruncated(), r.getProgressRows(), r.getErrorCode(), r.getErrorMessage(),
                r.getCreatedAt(), r.getStartedAt(), r.getFinishedAt(), r.getExpiresAt(), downloadable);
    }
}
