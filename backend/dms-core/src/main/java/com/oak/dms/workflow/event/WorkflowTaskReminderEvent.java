package com.oak.dms.workflow.event;

import java.util.UUID;

/** A task's reminder lead time was reached - mail everyone still holding it after commit (decision 5, A6). */
public record WorkflowTaskReminderEvent(UUID tenantId, UUID instanceId, UUID taskId) {
}
