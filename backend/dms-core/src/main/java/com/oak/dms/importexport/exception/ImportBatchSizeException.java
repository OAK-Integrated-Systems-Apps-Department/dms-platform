package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a bulk import breaks an intake rule - too many files, staged
 * files beyond the batch size limit, a submit with no accepted file, or
 * (when enforced) mandatory fields with no usable default. Maps to 400 Bad
 * Request.
 */
public class ImportBatchSizeException extends ApiException {
    public ImportBatchSizeException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
