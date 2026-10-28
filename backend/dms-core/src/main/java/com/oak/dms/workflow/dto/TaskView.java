package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** assigneeName is the user, role or department name. */
public record TaskView(
        UUID taskId,
        String assigneeType,
        UUID assigneeId,
        String assigneeName,
        String status,
        UserRef claimedBy,
        UserRef actedBy,
        String decision,
        String decisionReason,
        OffsetDateTime actedAt,
        OffsetDateTime dueAt,
        /** Original holder of a delegated or reassigned task (null when reassigned from an unclaimed pool). */
        UserRef delegatedFrom,
        UserRef delegatedTo,
        OffsetDateTime delegatedAt,
        /** The added escalation route; null with escalatedAt set means the target couldn't be used. */
        UserRef escalatedTo,
        OffsetDateTime escalatedAt,
        OffsetDateTime reminderSentAt,
        /** Set while the task awaits information - its SLA clock is paused. */
        OffsetDateTime slaPausedAt,
        int slaPausedSeconds,
        long openInfoRequestCount
) {
}
