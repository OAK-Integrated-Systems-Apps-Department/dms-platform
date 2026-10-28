package com.oak.dms.importexport.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A saved import template as stored. {@code missingFields} names columns
 * whose field is no longer on the document type's form (they're left out of
 * downloads) - a hint that the template needs editing.
 */
public record ImportTemplateResponse(
        UUID importTemplateId,
        UUID documentTypeId,
        String documentTypeName,
        String name,
        String description,
        List<ImportTemplateRequest.Column> columns,
        Map<String, String> defaults,
        List<String> missingFields,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
