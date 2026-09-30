package com.oak.dms.scanning.exception;

import org.springframework.http.HttpStatus;

/** The scan engine could not be reached. Retried; the file is held pending, never promoted. Maps to 503. */
public class ScanEngineUnavailableException extends ScanException {
    public ScanEngineUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
        initCause(cause);
    }
}
