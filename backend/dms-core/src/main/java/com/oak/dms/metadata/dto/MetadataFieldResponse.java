package com.oak.dms.metadata.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

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
        boolean indexed,
        boolean ocrExtractable,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
