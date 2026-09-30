package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a bulk import exceeds an intake limit - no data rows, too
 * many rows, an oversized template, or staged files beyond the batch size
 * limit. Maps to 400 Bad Request.
 */
public class ImportBatchSizeException extends ApiException {
    public ImportBatchSizeException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
