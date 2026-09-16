package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One row of a {@code PUT /document-types/{id}/fields} request body - the
 * desired mapping state for a single field within the new schema version
 * being published. The full list replaces the document type's current
 * mapping set; see {@code DocumentTypeFieldServiceImpl#replaceMapping}.
 */
public record DocumentTypeFieldMappingItem(

        @NotNull
        UUID fieldId,

        boolean mandatory,
        boolean readonly,
        boolean hidden,
        int displayOrder,

        @Size(max = 100)
        String fieldGroup,

        @Size(max = 500)
        String defaultValueOverride
) {
}
