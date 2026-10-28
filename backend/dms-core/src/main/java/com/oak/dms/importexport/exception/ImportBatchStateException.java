package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a file is added to, or a submit is attempted on, a bulk
 * import that is no longer in {@code uploading}. Maps to 409 Conflict.
 */
public class ImportBatchStateException extends ApiException {
    public ImportBatchStateException(String message) {
        super(HttpStatus.CONFLICT, "IMPORT_BATCH_NOT_UPLOADING", message);
    }
}
