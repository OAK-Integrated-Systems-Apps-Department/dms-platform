package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * An upload refused because it would duplicate something - a new version
 * identical to the current one (VERSION_IDENTICAL), or a file someone else
 * uploaded into the same folder a moment earlier (DUPLICATE_UPLOADED_MEANWHILE).
 */
public class DuplicateUploadException extends ApiException {
    public DuplicateUploadException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
