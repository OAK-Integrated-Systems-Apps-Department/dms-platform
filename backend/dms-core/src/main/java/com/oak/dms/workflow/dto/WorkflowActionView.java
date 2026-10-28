package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** One timeline entry. actor is null for system actions. */
public record WorkflowActionView(
        UUID actionId,
        String actionType,
        UserRef actor,
        String reason,
        Map<String, Object> detail,
        UUID stageInstanceId,
        Integer stageOrder,
        UUID taskId,
        OffsetDateTime occurredAt
) {
}
