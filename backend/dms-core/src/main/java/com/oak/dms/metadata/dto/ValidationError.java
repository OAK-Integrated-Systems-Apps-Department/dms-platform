package com.oak.dms.metadata.dto;

/**
 * One field-level validation failure returned by
 * {@link com.oak.dms.metadata.MetadataValidationService}. {@code errorCode}
 * is a stable machine-readable discriminator (e.g. {@code MANDATORY_FIELD_MISSING},
 * {@code NO_ACTIVE_OPTIONS}, or the rule's {@code rule_type} in upper snake
 * case such as {@code MAX_LENGTH}); {@code message} is the human-readable
 * text to surface next to the field.
 */
public record ValidationError(
        String fieldKey,
        String errorCode,
        String message,
        String severity
) {
}
