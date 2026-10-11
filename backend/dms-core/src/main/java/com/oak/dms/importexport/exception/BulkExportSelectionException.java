package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a bulk export's selection is malformed, matches no document
 * the caller may export, or is over the document-count or size limit.
 * Maps to 400 Bad Request.
 */
public class BulkExportSelectionException extends ApiException {
    public BulkExportSelectionException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
