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
        OffsetDateTime dueAt
) {
}
