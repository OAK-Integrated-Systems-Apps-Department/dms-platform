package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for adding a new field to the tenant's metadata schema
 * registry. {@code dataType} is validated against
 * {@link com.oak.dms.metadata.MetadataField#DATA_TYPES} in the service
 * layer, not via a Java enum - see that field's own doc comment for why
 * ('boolean' is a reserved word).
 */
public record CreateMetadataFieldRequest(

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "must be lower_snake_case, starting with a letter")
        String fieldKey,

        @NotBlank
        @Size(max = 150)
        String label,

        @Size(max = 500)
        String helpText,

        @NotBlank
        @Pattern(regexp = "^(text|long_text|number|decimal|date|datetime|boolean|dropdown|multi_select|user_ref|lookup)$",
                message = "must be one of: text, long_text, number, decimal, date, datetime, boolean, dropdown, multi_select, user_ref, lookup")
        String dataType,

        @Size(max = 500)
        String defaultValue,

        boolean systemField,
        boolean searchable,
        boolean ocrExtractable
) {
}
