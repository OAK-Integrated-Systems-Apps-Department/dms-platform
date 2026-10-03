package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the failed-rows template is requested for a bulk import that
 * isn't {@code completed_with_errors} or {@code failed}. Maps to 409 Conflict.
 */
public class ImportFailedRowsUnavailableException extends ApiException {
    public ImportFailedRowsUnavailableException(String message) {
        super(HttpStatus.CONFLICT, "IMPORT_FAILED_ROWS_UNAVAILABLE", message);
    }
}
