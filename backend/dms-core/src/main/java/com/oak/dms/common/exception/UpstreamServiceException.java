package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a call to an internal upstream service (MinIO, ocr-worker)
 * fails or is unreachable. Deliberately distinct from a bare
 * {@link IllegalStateException} -- {@link GlobalExceptionHandler} maps
 * that unconditionally to 401 TENANT_CONTEXT_MISSING, which would be
 * actively misleading here: this is a real infrastructure failure, not a
 * provisioning gap. Maps to 502 Bad Gateway.
 */
public class UpstreamServiceException extends ApiException {
    public UpstreamServiceException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "UPSTREAM_SERVICE_ERROR", message);
        if (cause != null) {
            initCause(cause);
        }
    }
}
