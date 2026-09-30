package com.oak.dms.scanning.exception;

import org.springframework.http.HttpStatus;

/** The upload exceeds the scan engine's {@code max_file_size_bytes}. Rejected before (or while) staging. Maps to 413. */
public class FileTooLargeException extends ScanException {
    public FileTooLargeException(long maxBytes) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File exceeds the maximum upload size of " + maxBytes + " bytes");
    }
}
