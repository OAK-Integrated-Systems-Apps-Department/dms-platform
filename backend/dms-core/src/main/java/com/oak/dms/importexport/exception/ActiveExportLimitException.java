package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the caller already has as many bulk exports pending or
 * processing as {@code dms.import-export.max-active-exports-per-user}
 * allows. Maps to 409 Conflict.
 */
public class ActiveExportLimitException extends ApiException {
    public ActiveExportLimitException(String message) {
        super(HttpStatus.CONFLICT, "EXPORT_ACTIVE_LIMIT", message);
    }
}
