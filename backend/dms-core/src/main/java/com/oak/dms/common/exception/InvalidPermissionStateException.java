package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an operation requires a permission to be in a specific
 * state it isn't currently in — e.g. trying to edit or delete a shared/
 * platform-default permission (tenantId null) from a single tenant's
 * Permission Catalog screens, when only that tenant's own custom
 * permissions can be modified there. Maps to 409 Conflict, same status
 * family as {@link DuplicateResourceException}, {@link InvalidUserStateException},
 * and {@link InvalidRoleStateException} (the request is well-formed, but
 * conflicts with the resource's current state — not a not-found or a
 * plain validation error).
 */
public class InvalidPermissionStateException extends ApiException {
    public InvalidPermissionStateException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
