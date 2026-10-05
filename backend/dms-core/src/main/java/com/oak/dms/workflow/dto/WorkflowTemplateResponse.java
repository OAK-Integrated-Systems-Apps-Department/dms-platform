package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** hasInstances: some instance ran on this version - it can never be deleted. */
public record WorkflowTemplateResponse(
        UUID templateId,
        String code,
        String name,
        String description,
        int versionNumber,
        String status,
        UUID documentTypeId,
        String documentTypeName,
        String onDocumentChange,
        boolean hasInstances,
        List<WorkflowStageResponse> stages,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
