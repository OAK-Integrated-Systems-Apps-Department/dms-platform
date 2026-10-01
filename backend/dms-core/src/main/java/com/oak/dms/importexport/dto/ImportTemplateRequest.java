package com.oak.dms.importexport.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Create or edit a saved import template. {@code documentTypeId} is only
 * read on create - a template can't move to another document type.
 *
 * @param columns  metadata columns in order (File name and Title always come first)
 * @param defaults field_key -> value used when that field is left out or left blank
 */
public record ImportTemplateRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String description,
        UUID documentTypeId,
        @NotNull @Size(max = 200) List<@Valid Column> columns,
        Map<String, String> defaults
) {

    /** @param header the column's name in the file; blank = the field's label */
    public record Column(@NotBlank String fieldKey, @Size(max = 100) String header) {
    }
}
