package com.oak.dms.scanning.exception;

import org.springframework.http.HttpStatus;

/** The scan engine did not answer within the engine's {@code timeout_seconds}. Retried like an outage. Maps to 504. */
public class ScanTimeoutException extends ScanException {
    public ScanTimeoutException(String message, Throwable cause) {
        super(HttpStatus.GATEWAY_TIMEOUT, message);
        initCause(cause);
    }
}
