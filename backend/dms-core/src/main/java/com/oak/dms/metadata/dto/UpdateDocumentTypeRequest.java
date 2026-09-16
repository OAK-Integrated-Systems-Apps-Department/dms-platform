package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code typeCode} is immutable after creation, same reasoning as
 * {@code MetadataField.fieldKey} - not accepted here. Status transitions go
 * through the dedicated activate/deactivate endpoints instead.
 */
public record UpdateDocumentTypeRequest(

        @NotBlank
        @Size(max = 150)
        String typeName,

        @Size(max = 500)
        String description,

        @Size(max = 50)
        String icon,

        int displayOrder,
        boolean requiresWatermark,
        boolean coeditingEnabled
) {
}
