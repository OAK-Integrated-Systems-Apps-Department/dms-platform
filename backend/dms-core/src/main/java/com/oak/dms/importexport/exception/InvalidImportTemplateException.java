package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an uploaded bulk-import template can't be used - it isn't a
 * workbook, its hidden {@code _meta} sheet is missing or unreadable, it
 * references a document type outside the caller's tenant, or it was
 * generated for an older schema version. The message tells the user to
 * download a fresh template. Maps to 400 Bad Request.
 */
public class InvalidImportTemplateException extends ApiException {
    public InvalidImportTemplateException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
