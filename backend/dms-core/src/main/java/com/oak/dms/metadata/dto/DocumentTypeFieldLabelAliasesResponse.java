package com.oak.dms.metadata.dto;

import java.util.List;
import java.util.UUID;

/**
 * The OCR label hints scoped to one (document type, field) pair, as
 * returned by {@code GET}/{@code PUT /document-types/{documentTypeId}/fields/{fieldId}/label-aliases}.
 * Only the type-scoped list - the field's global hints are on
 * {@link MetadataFieldResponse#labelAliases()} instead.
 */
public record DocumentTypeFieldLabelAliasesResponse(
        UUID documentTypeId,
        UUID fieldId,
        List<String> labelAliases
) {
}
