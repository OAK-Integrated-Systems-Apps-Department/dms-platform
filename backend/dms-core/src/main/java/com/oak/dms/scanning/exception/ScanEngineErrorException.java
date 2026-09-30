package com.oak.dms.scanning.exception;

/** The scan engine answered, but with an error instead of a verdict (clamd ERROR, GuardDuty FAILED/ACCESS_DENIED, ...). Retried. Maps to 502. */
public class ScanEngineErrorException extends ScanException {
    public ScanEngineErrorException(String message) {
        super(message);
    }

    public ScanEngineErrorException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
