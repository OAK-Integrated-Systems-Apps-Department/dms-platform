package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkflowTemplateSummaryResponse(
        UUID templateId,
        String code,
        String name,
        int versionNumber,
        String status,
        UUID documentTypeId,
        String documentTypeName,
        long stageCount,
        String onDocumentChange,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
