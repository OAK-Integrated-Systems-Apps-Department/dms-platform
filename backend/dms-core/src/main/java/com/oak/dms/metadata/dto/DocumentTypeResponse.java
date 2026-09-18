package com.oak.dms.metadata.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentTypeResponse(
        UUID documentTypeId,
        UUID tenantId,
        String typeCode,
        String typeName,
        String description,
        int displayOrder,
        int currentSchemaVersion,
        UUID defaultRetentionPolicyId,
        boolean requiresWatermark,
        boolean coeditingEnabled,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
