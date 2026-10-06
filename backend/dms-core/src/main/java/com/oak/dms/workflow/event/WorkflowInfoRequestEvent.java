package com.oak.dms.workflow.event;

import java.util.List;
import java.util.UUID;

/**
 * Something happened to information requests of one instance (Phase 2):
 * kind {@code requested} | {@code answered} | {@code declined} |
 * {@code withdrawn} | {@code reassigned} | {@code cancelled} (closed by the
 * engine because the stage or workflow moved on, or the task was
 * reassigned). Mailed after commit; {@code previousRequestedFrom} is set
 * only for {@code reassigned}.
 */
public record WorkflowInfoRequestEvent(UUID tenantId, UUID instanceId, List<UUID> infoRequestIds, String kind,
                                       UUID actorUserId, UUID previousRequestedFrom) {
}
