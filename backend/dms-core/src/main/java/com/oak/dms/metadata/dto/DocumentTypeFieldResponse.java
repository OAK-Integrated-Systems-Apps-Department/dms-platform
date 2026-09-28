package com.oak.dms.metadata.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code labelAliases} is the OCR label hints scoped to this document type
 * for this field (tried before the field's global hints, which are NOT
 * repeated here - see {@link MetadataFieldResponse#labelAliases()}), in
 * priority order, never null. Hints aren't versioned, so a historical
 * {@code ?version=N} row carries the same current list as the live row.
 */
public record DocumentTypeFieldResponse(
        UUID documentTypeFieldId,
        UUID documentTypeId,
        UUID fieldId,
        String fieldKey,
        String label,
        int schemaVersion,
        String status,
        boolean mandatory,
        boolean hidden,
        boolean requiresBackfill,
        int displayOrder,
        String fieldGroup,
        String defaultValueOverride,
        OffsetDateTime archivedAt,
        List<String> labelAliases
) {
}
