package com.oak.dms.scanning.exception;

import org.springframework.http.HttpStatus;

/** A scan engine or staging location is misconfigured (unregistered provider, incompatible staging, missing credentials, ...). Maps to 500. */
public class ScanConfigurationException extends ScanException {
    public ScanConfigurationException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
