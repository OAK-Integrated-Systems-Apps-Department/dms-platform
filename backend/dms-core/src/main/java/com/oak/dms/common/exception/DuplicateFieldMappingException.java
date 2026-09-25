package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a document-type field mapping publish lists the same
 * {@code fieldId} more than once. The mapping for a version is a single
 * JSONB array (see {@code DocumentTypeSchema}), so there is no database
 * unique constraint to catch this - it's rejected up front instead, the
 * same way duplicate {@code displayOrder} values are. Maps to 400 Bad Request.
 */
public class DuplicateFieldMappingException extends ApiException {
    public DuplicateFieldMappingException(String message) {
        super(HttpStatus.BAD_REQUEST, "DUPLICATE_FIELD_MAPPING", message);
    }
}
