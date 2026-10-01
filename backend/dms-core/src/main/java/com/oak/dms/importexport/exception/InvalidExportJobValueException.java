package com.oak.dms.importexport.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A write to export_jobs carried a value outside its enum (export_type or
 * status) or left a required one out. Raised at the application layer so a
 * bad value is a clean 400 naming the allowed values, never a raw database
 * constraint violation.
 */
public class InvalidExportJobValueException extends ApiException {

    public InvalidExportJobValueException(String field, String value, Enum<?>[] allowed) {
        super(HttpStatus.BAD_REQUEST, "INVALID_EXPORT_JOB_VALUE",
                "Invalid " + field + " '" + value + "'. Allowed: " + Arrays.stream(allowed)
                        .map(Enum::name).collect(Collectors.joining(", ")));
    }

    public InvalidExportJobValueException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_EXPORT_JOB_VALUE", message);
    }
}
