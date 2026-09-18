package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a document-type field mapping request references a
 * {@link com.oak.dms.metadata.MetadataField} whose status is
 * {@code deprecated} - deprecation is meant to stop a field from being
 * mapped onto new or edited schemas while leaving it usable on documents
 * that already reference it. Maps to 400 Bad Request.
 */
public class DeprecatedFieldMappingException extends ApiException {
    public DeprecatedFieldMappingException(String message) {
        super(HttpStatus.BAD_REQUEST, "DEPRECATED_FIELD_IN_MAPPING", message);
    }
}
