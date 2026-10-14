package com.oak.dms.workflow.event;

import java.util.UUID;

/**
 * A task moved to another holder - delegated by its holder, or reassigned
 * by an admin (kind {@code delegated} | {@code reassigned}). Both the
 * previous holder (null when reassigned from an unclaimed pool) and the new
 * one are mailed after commit (decision 2). {@code newHolderId} is null
 * when a delegation was handed back to the original holder.
 */
public record WorkflowTaskDelegatedEvent(UUID tenantId, UUID instanceId, UUID taskId, String kind,
                                         UUID previousHolderId, UUID newHolderId, UUID actorUserId, String note) {
}
