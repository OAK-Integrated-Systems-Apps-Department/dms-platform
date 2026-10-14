package com.oak.dms.workflow.event;

import java.util.List;
import java.util.UUID;

/** New tasks were created when a stage activated - mail their assignees after commit (WA-FR-006). */
public record WorkflowTaskAssignedEvent(UUID tenantId, UUID instanceId, List<UUID> taskIds) {
}
