package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested resource does not exist, or exists but belongs to
 * a different tenant (see {@link com.oak.dms.common.exception.TenantMismatchException},
 * which also maps here). Maps to 404 Not Found.
 */
public class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException(String resource, Object identifier) {
        super(HttpStatus.NOT_FOUND, resource + " not found: " + identifier);
    }
}
