package com.oak.dms.scanning.exception;

import org.springframework.http.HttpStatus;

/** A cloud verdict callback arrived without the configured shared secret (or the endpoint is disabled). Maps to 401. */
public class ScanCallbackAuthenticationException extends ScanException {
    public ScanCallbackAuthenticationException() {
        super(HttpStatus.UNAUTHORIZED, "Scan callback not authenticated");
    }
}
