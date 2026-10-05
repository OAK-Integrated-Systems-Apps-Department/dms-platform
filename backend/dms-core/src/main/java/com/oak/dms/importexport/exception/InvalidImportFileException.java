package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an upload into a bulk import can't even be recorded as an
 * item - it has no filename. Files that are recorded but refused (type,
 * empty, password-protected, batch size) throw their own exceptions. Maps
 * to 400 Bad Request.
 */
public class InvalidImportFileException extends ApiException {
    public InvalidImportFileException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
