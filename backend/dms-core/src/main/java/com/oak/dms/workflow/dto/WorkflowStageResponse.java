package com.oak.dms.workflow.dto;

import java.util.List;
import java.util.UUID;

public record WorkflowStageResponse(
        UUID stageId,
        int stageOrder,
        String name,
        String completionRule,
        String rejectionRule,
        Integer slaHours,
        Integer reminderBeforeHours,
        /** null = inherit the template default */
        Integer inactivityReminderHours,
        Integer escalateAfterReminderHours,
        String escalationTarget,
        UUID escalationUserId,
        boolean allowInfoRequests,
        boolean requiresSignature,
        List<ParticipantResponse> participants
) {
}
