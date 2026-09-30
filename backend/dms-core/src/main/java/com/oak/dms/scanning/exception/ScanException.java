package com.oak.dms.scanning.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Base type every {@link com.oak.dms.scanning.service.ScanService} adapter and
 * the ingestion gate translate their failures into, so callers and
 * {@link com.oak.dms.common.exception.GlobalExceptionHandler} only ever see
 * this hierarchy - never a socket exception, an Azure/AWS SDK exception, or a
 * provider-specific type. Mirrors {@link com.oak.dms.storage.exception.StorageException}.
 * <p>
 * Default status is 502 Bad Gateway: the scan engine itself failed.
 */
public abstract class ScanException extends ApiException {

    protected ScanException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    protected ScanException(HttpStatus status, String message) {
        super(status, message);
    }

    protected ScanException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
