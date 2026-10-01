package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the caller already has as many bulk imports uploading,
 * queued, or running as {@code dms.import-export.max-active-batches-per-user}
 * allows. Maps to 409 Conflict.
 */
public class ActiveImportBatchLimitException extends ApiException {
    public ActiveImportBatchLimitException(String message) {
        super(HttpStatus.CONFLICT, "IMPORT_ACTIVE_BATCH_LIMIT", message);
    }
}
