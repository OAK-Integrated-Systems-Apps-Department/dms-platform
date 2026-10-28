package com.oak.dms.workflow.helper;

import com.oak.dms.workflow.entity.WorkflowAction;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.repository.WorkflowActionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the instance timeline (workflow_actions). Separate from the audit
 * trail on purpose (decision 13): this is what the UI shows; audit_events is
 * the compliance record.
 */
@Component
@RequiredArgsConstructor
public class WorkflowActionRecorder {

    private final WorkflowActionRepository actionRepository;

    public WorkflowAction record(WorkflowInstance instance, UUID stageInstanceId, UUID taskId,
                                 WorkflowActionType type, UUID actorUserId, String reason, Map<String, Object> detail) {
        return record(instance, stageInstanceId, taskId, null, type, actorUserId, reason, detail);
    }

    /** Same, for an entry about an information request (info_*, or a supporting document added in a response). */
    public WorkflowAction record(WorkflowInstance instance, UUID stageInstanceId, UUID taskId, UUID infoRequestId,
                                 WorkflowActionType type, UUID actorUserId, String reason, Map<String, Object> detail) {
        return actionRepository.save(WorkflowAction.builder()
                .tenantId(instance.getTenantId())
                .instanceId(instance.getInstanceId())
                .stageInstanceId(stageInstanceId)
                .taskId(taskId)
                .infoRequestId(infoRequestId)
                .actionType(type)
                .actorUserId(actorUserId)
                .reason(reason)
                .detail(detail == null ? new HashMap<>() : new HashMap<>(detail))
                .occurredAt(OffsetDateTime.now())
                .build());
    }
}
