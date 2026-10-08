package com.oak.dms.workflow.dto;

import java.util.UUID;

/** Result of claim/release/approve/reject: the task, its stage and its instance after the action. */
public record TaskActionResponse(
        UUID taskId,
        String taskStatus,
        UUID stageInstanceId,
        String stageStatus,
        int approvedCount,
        int rejectedCount,
        int requiredCount,
        UUID instanceId,
        String instanceStatus,
        UUID currentStageInstanceId
) {
}
