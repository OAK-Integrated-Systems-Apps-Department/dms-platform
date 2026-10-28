package com.oak.dms.scanning.exception;

import org.springframework.http.HttpStatus;

/** The upload exceeds the scan engine's {@code max_file_size_bytes}. Rejected before (or while) staging. Maps to 413. */
public class FileTooLargeException extends ScanException {
    public FileTooLargeException(long maxBytes) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", message(maxBytes));
    }

    /** "This file is larger than the 2047 MB upload limit." - also used for the servlet layer's own refusal. */
    public static String message(long maxBytes) {
        String limit = maxBytes >= 1024 * 1024 ? (maxBytes / (1024 * 1024)) + " MB" : maxBytes + " bytes";
        return "This file is larger than the " + limit + " upload limit, so it can't be uploaded.";
    }
}
