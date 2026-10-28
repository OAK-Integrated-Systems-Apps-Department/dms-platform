package com.oak.dms.watermark.enums;

import com.oak.dms.watermark.exception.WatermarkException;

/**
 * Lifecycle of a watermark job (watermark_jobs.status). Constant names are
 * the stored values, lowercase, exactly like {@code ExportJobStatus} - see
 * ck_watermark_jobs_status in V68.
 * <p>
 * {@code expired} is set by {@code WatermarkOutputSweeper} once the output
 * has been deleted: the job itself stays as the audit record of what was
 * produced, only the file goes.
 */
public enum WatermarkJobStatus {
    pending,
    processing,
    completed,
    failed,
    expired;

    public static WatermarkJobStatus fromValue(String value) {
        for (WatermarkJobStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw WatermarkException.badRequest("WATERMARK_INVALID_STATUS",
                "status must be one of pending, processing, completed, failed, expired - got '" + value + "'.");
    }
}
