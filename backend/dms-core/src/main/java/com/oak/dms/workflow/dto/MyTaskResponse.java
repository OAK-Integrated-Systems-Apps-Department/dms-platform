package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the caller's My Tasks queue (W10, Phase 2). poolName is the role/department name for a pool
 * task; overdue is never true while the task awaits information (its SLA clock is paused).
 */
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
        boolean versionChangedFlag,
        /** assigned | pool | claimed | delegated | escalated - how the caller reaches this task. */
        String route,
        /** The original holder when the task was delegated (or reassigned) to the caller. */
        UserRef delegatedFrom,
        long openInfoRequestCount,
        /** Whether approving this task's stage requires a digital signature (V105) - the frontend opens sign-and-approve instead of a plain approve when true. */
        boolean requiresSignature
) {
}
