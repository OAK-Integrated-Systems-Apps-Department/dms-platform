package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of a document's workflow history. */
public record WorkflowInstanceSummaryResponse(
        UUID instanceId,
        String templateName,
        int templateVersionNumber,
        String status,
        UserRef initiatedBy,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String outcomeReason,
        boolean versionChangedFlag
) {
}
