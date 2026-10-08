package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when every row whose Filename matches an uploaded file already has
 * a staged file. Maps to 409 Conflict.
 */
public class ImportFileAlreadyReceivedException extends ApiException {
    public ImportFileAlreadyReceivedException(String message) {
        super(HttpStatus.CONFLICT, "IMPORT_FILE_ALREADY_RECEIVED", message);
    }
}
