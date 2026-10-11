package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the caller's bulk export has no file to download: not
 * finished (or failed) - 409 Conflict - or expired - 410 Gone.
 */
public class ExportFileUnavailableException extends ApiException {

    private ExportFileUnavailableException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static ExportFileUnavailableException notReady(String status) {
        return new ExportFileUnavailableException(HttpStatus.CONFLICT, "EXPORT_NOT_READY",
                "This export has no file to download (status: " + status + ").");
    }

    public static ExportFileUnavailableException expired() {
        return new ExportFileUnavailableException(HttpStatus.GONE, "EXPORT_EXPIRED",
                "This export has expired and its file has been deleted. Request a new export.");
    }
}
