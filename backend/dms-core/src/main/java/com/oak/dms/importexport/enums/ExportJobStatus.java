package com.oak.dms.importexport.enums;

import com.oak.dms.importexport.exception.InvalidExportJobValueException;

/**
 * Lifecycle of an export job (export_jobs.status). Constant names are the
 * stored values (see ck_export_jobs_status in V41).
 */
public enum ExportJobStatus {
    pending,
    processing,
    completed,
    failed,
    expired;

    /** Strict parse - see {@link ExportType#fromValue(String)}. */
    public static ExportJobStatus fromValue(String value) {
        for (ExportJobStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new InvalidExportJobValueException("status", value, values());
    }
}
