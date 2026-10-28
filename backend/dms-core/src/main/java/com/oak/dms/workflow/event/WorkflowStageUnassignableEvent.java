package com.oak.dms.workflow.event;

import java.util.List;
import java.util.UUID;

/**
 * A stage activated with entries whose pool has nobody eligible - the tasks
 * are parked. Mails reassign-permission holders, tenant admins and the
 * initiator (Phase 2 reassign resolves it).
 */
public record WorkflowStageUnassignableEvent(UUID tenantId, UUID instanceId, UUID stageInstanceId,
                                             List<UUID> taskIds) {
}
