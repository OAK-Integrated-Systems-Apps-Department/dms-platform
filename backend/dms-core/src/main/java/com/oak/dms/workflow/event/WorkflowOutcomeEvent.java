package com.oak.dms.workflow.event;

import java.util.UUID;

/**
 * An instance reached approved, rejected or cancelled - mail the initiator
 * after commit (WA-FR-014). {@code actorUserId} is who caused it (null for
 * the system); a cancel by the initiator themselves isn't mailed back to them.
 */
public record WorkflowOutcomeEvent(UUID tenantId, UUID instanceId, UUID actorUserId) {
}
