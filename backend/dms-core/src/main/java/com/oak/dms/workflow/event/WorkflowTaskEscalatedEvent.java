package com.oak.dms.workflow.event;

import java.util.UUID;

/**
 * A task passed its due time. {@code targetUserId} is the added route (mail
 * them, with a copy to the current holders); when null the target couldn't
 * be used ({@code skippedReason}) and the reassign-permission holders and
 * tenant admins are alerted instead (A2).
 */
public record WorkflowTaskEscalatedEvent(UUID tenantId, UUID instanceId, UUID taskId, UUID targetUserId,
                                         String skippedReason) {
}
