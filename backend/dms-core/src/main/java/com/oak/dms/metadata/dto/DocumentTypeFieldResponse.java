package com.oak.dms.metadata.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentTypeFieldResponse(
        UUID documentTypeFieldId,
        UUID documentTypeId,
        UUID fieldId,
        String fieldKey,
        String label,
        int schemaVersion,
        String status,
        boolean mandatory,
        boolean readonly,
        boolean hidden,
        boolean requiresBackfill,
        int displayOrder,
        String fieldGroup,
        String defaultValueOverride,
        OffsetDateTime archivedAt
) {
}
