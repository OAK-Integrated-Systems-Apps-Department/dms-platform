package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a bulk import is started (or its defaults are asked for) for
 * a document type that has been deactivated. Maps to 400 Bad Request.
 */
public class InactiveDocumentTypeException extends ApiException {
    public InactiveDocumentTypeException(String message) {
        super(HttpStatus.BAD_REQUEST, "DOCUMENT_TYPE_INACTIVE", message);
    }
}
