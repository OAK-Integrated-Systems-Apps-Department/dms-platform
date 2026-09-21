package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Body of {@code POST /document-types/{documentTypeId}/validate} - candidate
 * field values to run through {@link com.oak.dms.metadata.service.MetadataValidationService}
 * without persisting anything.
 * <p>
 * {@code schemaVersion} is optional; when omitted, validation runs against
 * the document type's current version - the only path that checks
 * {@code documentTypeId} actually exists.
 * <p>
 * {@code values} may legitimately be an empty map, e.g. to see every
 * {@code MANDATORY_FIELD_MISSING} a blank form would produce.
 */
public record ValidateMetadataRequest(

        Integer schemaVersion,

        @NotNull
        @Size(max = 200, message = "values must not contain more than 200 entries")
        Map<String, Object> values
) {
}
