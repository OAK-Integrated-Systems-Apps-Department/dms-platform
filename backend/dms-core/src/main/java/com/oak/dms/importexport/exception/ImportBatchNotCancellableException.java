package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a cancel is attempted on a bulk import that is no longer in
 * {@code uploading} or {@code queued}. Maps to 409 Conflict.
 */
public class ImportBatchNotCancellableException extends ApiException {
    public ImportBatchNotCancellableException(String message) {
        super(HttpStatus.CONFLICT, "IMPORT_BATCH_NOT_CANCELLABLE", message);
    }
}
