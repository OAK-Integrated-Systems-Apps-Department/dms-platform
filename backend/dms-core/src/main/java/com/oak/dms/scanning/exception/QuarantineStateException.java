package com.oak.dms.scanning.exception;

import org.springframework.http.HttpStatus;

/**
 * The quarantine record isn't in a state that allows the requested action -
 * not {@code held}, a release already in progress, or an override requested
 * before any re-scan was refused. Maps to 409.
 */
public class QuarantineStateException extends ScanException {
    public QuarantineStateException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
