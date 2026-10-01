package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an uploaded file's name doesn't match any row's Filename in
 * the batch's template. Maps to 400 Bad Request.
 */
public class ImportFileNotInBatchException extends ApiException {
    public ImportFileNotInBatchException(String message) {
        super(HttpStatus.BAD_REQUEST, "IMPORT_FILE_NOT_IN_BATCH", message);
    }
}
