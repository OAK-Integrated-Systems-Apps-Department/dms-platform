package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDocumentTypeRequest(

        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "must be UPPER_SNAKE_CASE, starting with a letter")
        String typeCode,

        @NotBlank
        @Size(max = 150)
        String typeName,

        @Size(max = 500)
        String description,

        int displayOrder,
        boolean requiresWatermark,
        boolean coeditingEnabled
) {
}
