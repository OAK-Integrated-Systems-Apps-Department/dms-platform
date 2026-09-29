package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for exceptions that should be translated straight into an HTTP
 * status by {@link GlobalExceptionHandler}, rather than surfacing as a 500.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String message) {
        this(status, null, message);
    }

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
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
