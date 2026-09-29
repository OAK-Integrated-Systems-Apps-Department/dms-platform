package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an operation requires a role to be in a specific state it
 * isn't currently in, or would put it into a state that conflicts with
 * something else about it right now — e.g. deactivating a role that's
 * still assigned to at least one user. Maps to 409 Conflict, same status
 * family as {@link DuplicateResourceException} and
 * {@link InvalidUserStateException} (the request is well-formed, but
 * conflicts with the resource's current state — not a not-found or a
 * plain validation error).
 */
public class InvalidRoleStateException extends ApiException {
    public InvalidRoleStateException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
