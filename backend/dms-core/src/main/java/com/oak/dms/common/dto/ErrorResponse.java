package com.oak.dms.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Uniform error body returned by {@link com.oak.dms.common.exception.GlobalExceptionHandler}
 * for every failure path, so API consumers only ever parse one error shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldValidationError> fieldErrors,
        Object details
) {
    /** Without {@code details} - the shape every error had before it was added. */
    public ErrorResponse(OffsetDateTime timestamp, int status, String error, String code, String message, String path,
                         List<FieldValidationError> fieldErrors) {
        this(timestamp, status, error, code, message, path, fieldErrors, null);
    }

    public record FieldValidationError(String field, String message) {
    }

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, null, message, path, null);
    }

    public static ErrorResponse of(int status, String error, String code, String message, String path) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, code, message, path, null);
    }

    public static ErrorResponse ofValidation(int status, String error, String message, String path,
                                              List<FieldValidationError> fieldErrors) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, null, message, path, fieldErrors);
    }

    /** An error that also carries structured data the caller can act on (a loop's chain, the invalid targets of a bulk request). */
    public static ErrorResponse ofDetails(int status, String error, String code, String message, String path,
                                          Object details) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, code, message, path, null, details);
    }
}
