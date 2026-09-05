package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an operation requires the target to be in a specific state
 * it isn't currently in — e.g. reactivating a user that was never
 * deactivated. Maps to 409 Conflict, same status family as
 * {@link DuplicateResourceException} (both are "the request is valid but
 * conflicts with the resource's current state", not a not-found or a
 * validation error).
 */
public class InvalidUserStateException extends ApiException {
    public InvalidUserStateException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}