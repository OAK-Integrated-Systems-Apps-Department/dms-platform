package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Something wrong with a bulk import request as a whole (the template, the
 * files, an upload session). A single row's problems never throw - they are
 * recorded on its migration_items row.
 */
public class BulkImportException extends ApiException {

    public BulkImportException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static BulkImportException badRequest(String code, String message) {
        return new BulkImportException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static BulkImportException conflict(String code, String message) {
        return new BulkImportException(HttpStatus.CONFLICT, code, message);
    }
}
