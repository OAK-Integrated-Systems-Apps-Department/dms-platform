package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for exceptions that should be translated straight into an HTTP
 * status by {@link GlobalExceptionHandler}, rather than surfacing as a 500.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object details;

    protected ApiException(HttpStatus status, String message) {
        this(status, null, message);
    }

    protected ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    /** With structured data for the response body's {@code details} (see ErrorResponse). */
    protected ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    /** Structured data for the response body, or null. */
    public Object getDetails() {
        return details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /**
     * Optional machine-readable error code (e.g. {@code KEYCLOAK_USER_ALREADY_EXISTS})
     * for callers that need to branch on the failure reason rather than
     * pattern-match {@link #getMessage()}. Null for exceptions that don't
     * define one - {@link GlobalExceptionHandler} omits the field from the
     * response in that case.
     */
    public String getCode() {
        return code;
    }
}
