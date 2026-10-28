package com.oak.dms.workflow.helper;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The lock sequence of every path that changes one task outside a decision
 * (delegate, reassign, information requests): instance, then stage
 * instance, then the task - a prefix of the global order (decision 22).
 * Parent ids are read as scalars first so that nothing is in the
 * persistence context before its FOR UPDATE (a stale copy would come back).
 */
@Component
@RequiredArgsConstructor
public class TaskLocker {

    public record Locked(WorkflowInstance instance, WorkflowStageInstance stage, WorkflowTask task) {
    }

    private final WorkflowLocks locks;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowInstanceRepository instanceRepository;

    /** 404 when the task isn't in the tenant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Locked lock(UUID taskId, UUID tenantId) {
        locks.applyLockTimeout();
        UUID stageInstanceId = taskRepository.findStageInstanceIdOfTask(taskId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId));
        UUID instanceId = stageInstanceRepository.findInstanceIdOfStage(stageInstanceId, tenantId).orElseThrow();
        WorkflowInstance instance = instanceRepository.lockByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        WorkflowStageInstance stage = stageInstanceRepository.lockByStageInstanceIdAndTenantId(stageInstanceId, tenantId)
                .orElseThrow();
        WorkflowTask task = taskRepository.lockByTaskIdAndTenantId(taskId, tenantId).orElseThrow();
        return new Locked(instance, stage, task);
    }
}
