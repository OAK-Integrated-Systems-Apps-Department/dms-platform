package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Status of one instance (W8, WA-FR-005). nextAssignees are the open tasks
 * of the current stage; myOpenTaskId is the caller's actionable task, if any.
 */
public record WorkflowInstanceResponse(
        UUID instanceId,
        UUID documentId,
        String documentTitle,
        UUID documentVersionId,
        UUID latestDocumentVersionId,
        boolean versionChangedFlag,
        UUID templateId,
        String templateCode,
        String templateName,
        int templateVersionNumber,
        String status,
        UserRef initiatedBy,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String outcomeReason,
        UUID currentStageInstanceId,
        List<StageInstanceView> stages,
        List<TaskView> nextAssignees,
        UUID myOpenTaskId
) {
}
