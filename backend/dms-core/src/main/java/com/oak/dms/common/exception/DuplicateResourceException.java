package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a create/grant operation would violate a uniqueness rule the
 * caller could reasonably have avoided (e.g. a domain or role name already
 * in use). Maps to 409 Conflict.
 */
public class DuplicateResourceException extends ApiException {
    public DuplicateResourceException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
