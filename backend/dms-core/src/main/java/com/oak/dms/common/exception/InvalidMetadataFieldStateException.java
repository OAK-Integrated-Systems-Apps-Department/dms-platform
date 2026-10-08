package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an operation on a {@link com.oak.dms.metadata.entity.MetadataField}
 * conflicts with its current state - most notably, deleting a field still
 * mapped by a {@code document_type_schemas} version or holding a value in a
 * {@code document_metadata} row (MDTM alternate flow 2: "Administrator
 * attempts to delete a field still in use - system prevents deletion and
 * offers to deprecate the field instead"). Maps to 409 Conflict, same status
 * family as {@link InvalidRoleStateException}/{@link InvalidUserStateException}.
 */
public class InvalidMetadataFieldStateException extends ApiException {
    public InvalidMetadataFieldStateException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
