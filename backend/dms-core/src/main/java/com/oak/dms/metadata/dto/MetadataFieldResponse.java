package com.oak.dms.metadata.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code labelAliases} is the field's GLOBAL OCR label hints only (those
 * that apply in every document type the field is mapped into), in match
 * priority order - never null, empty when none are configured. Hints scoped
 * to a single document type are served by that type's field mapping
 * endpoints instead.
 */
public record MetadataFieldResponse(
        UUID fieldId,
        UUID tenantId,
        String fieldKey,
        String label,
        String helpText,
        String dataType,
        String defaultValue,
        boolean systemField,
        boolean searchable,
        boolean ocrExtractable,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<String> labelAliases
) {
}
