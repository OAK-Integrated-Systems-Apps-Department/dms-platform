package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for updating an existing field's descriptive/behavioral
 * attributes. {@code fieldKey} is immutable after creation (it's what
 * {@code document_type_fields}/{@code document_metadata_values} conceptually
 * key off), so it isn't accepted here. Status transitions (activate/
 * deactivate/deprecate) go through their own dedicated endpoints instead of
 * this general update, matching the Add/Edit Field screen's separate
 * "Deprecate" action.
 */
public record UpdateMetadataFieldRequest(

        @NotBlank
        @Size(max = 150)
        String label,

        @Size(max = 500)
        String helpText,

        @Size(max = 500)
        String defaultValue,

        boolean searchable,
        boolean ocrExtractable,

        // Global OCR label hints. Unlike every other property here, null/
        // omitted means "leave unchanged" rather than "clear", so an older
        // client (or any screen that doesn't show hints) can't wipe them just
        // by not knowing about the property; [] clears them, and any other
        // list replaces the global list wholesale, in the given order.
        List<String> labelAliases
) {
}
