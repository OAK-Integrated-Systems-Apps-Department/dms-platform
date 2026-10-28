package com.oak.dms.scanning.exception;

import org.springframework.http.HttpStatus;

/** The upload's type isn't in {@code dms.ingestion.allowed-file-types}, or its content doesn't match its extension. Rejected before staging. Maps to 415. */
public class FileTypeNotAllowedException extends ScanException {
    public FileTypeNotAllowedException(String message) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_TYPE_NOT_ALLOWED", message);
    }
}
