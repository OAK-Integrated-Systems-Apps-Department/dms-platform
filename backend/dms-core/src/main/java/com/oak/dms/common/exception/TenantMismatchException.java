package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request attempts to read or mutate a resource that belongs
 * to a different tenant than the caller's authenticated tenant context.
 * Returned as 404 rather than 403 so tenant boundaries never leak the
 * existence of another tenant's data.
 */
public class TenantMismatchException extends ApiException {
    public TenantMismatchException(String resource, Object identifier) {
        super(HttpStatus.NOT_FOUND, resource + " not found: " + identifier);
    }
}
