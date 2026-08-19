package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for exceptions that should be translated straight into an HTTP
 * status by {@link GlobalExceptionHandler}, rather than surfacing as a 500.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
