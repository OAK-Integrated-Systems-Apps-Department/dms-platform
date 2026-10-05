package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of the caller's My Tasks queue (W10). poolName is the role/department name for a pool task. */
public record MyTaskResponse(
        UUID taskId,
        UUID instanceId,
        UUID documentId,
        String documentTitle,
        String documentTypeName,
        String templateName,
        int stageOrder,
        String stageName,
        int totalStages,
        String assigneeType,
        String poolName,
        String status,
        boolean claimedByMe,
        OffsetDateTime dueAt,
        boolean overdue,
        OffsetDateTime activatedAt,
        UserRef initiatedBy,
        boolean versionChangedFlag
) {
}
