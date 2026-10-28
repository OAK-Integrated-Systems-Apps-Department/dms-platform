package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.workflow.dto.TaskActionResponse;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.StageInstanceStatus;
import com.oak.dms.workflow.enums.TaskDecision;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.exception.WorkflowAccessException;
import com.oak.dms.workflow.exception.WorkflowRequestException;
import com.oak.dms.workflow.exception.WorkflowStateException;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.helper.WorkflowLocks;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.ApproverResolver;
import com.oak.dms.workflow.service.WorkflowTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** See {@link WorkflowTaskService} and docs/workflow-phase1-plan.md §4/§6. */
@Service
@RequiredArgsConstructor
public class WorkflowTaskServiceImpl implements WorkflowTaskService {

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final ApproverResolver approverResolver;
    private final TaskRouting taskRouting;
    private final WorkflowEngine engine;
    private final WorkflowActionRecorder actions;
    private final WorkflowLocks locks;
    private final com.oak.dms.observability.DmsMetrics dmsMetrics;

    /**
     * No explicit lock: guards are read, then one conditional UPDATE that
     * holds only this task's row lock (§6). If the stage concludes in
     * between, the UPDATE waits on the approver's task lock, re-reads
     * status = pending and matches nothing.
     */
    @Override
    @Transactional
    public TaskActionResponse claim(UUID taskId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        locks.applyLockTimeout();
        WorkflowTask task = findTask(taskId, tenantId);
        WorkflowStageInstance stage = findStage(task, tenantId);
        WorkflowInstance instance = findInstance(stage, tenantId);
        requireActionable(instance, stage, userId);
        if (!task.isPool()) {
            throw new WorkflowStateException("WORKFLOW_TASK_NOT_CLAIMABLE",
                    "Only a role or department task can be claimed - this one is assigned to a person");
        }
        if (task.getDelegatedTo() != null) {
            throw new WorkflowStateException("WORKFLOW_TASK_DELEGATED", "This task has been delegated to someone");
        }
        if (TaskStatus.HELD.contains(task.getStatus())) {
            throw new WorkflowStateException("WORKFLOW_TASK_ALREADY_CLAIMED", "Someone has already claimed this task");
        }
        requireOpen(task);
        if (!approverResolver.isPoolMember(tenantId, task, userId)) {
            throw new WorkflowAccessException("WORKFLOW_NOT_ASSIGNEE", "You are not in this task's approver pool");
        }
        if (taskRepository.claim(taskId, tenantId, userId, OffsetDateTime.now()) == 0) {
            WorkflowTask now = findTask(taskId, tenantId);
            if (TaskStatus.HELD.contains(now.getStatus()) || now.getDelegatedTo() != null) {
                throw new WorkflowStateException("WORKFLOW_TASK_ALREADY_CLAIMED", "Someone has already claimed this task");
            }
            throw new WorkflowStateException("WORKFLOW_TASK_CLOSED", "This task is no longer open");
        }
        actions.record(instance, stage.getStageInstanceId(), taskId, WorkflowActionType.claimed, userId, null, null);
        taskAudit(AuditActionCatalog.WORKFLOW_TASK_CLAIMED, instance, taskId, stage, null, null).log();
        return response(findTask(taskId, tenantId), stage, instance);
    }

    @Override
    @Transactional
    public TaskActionResponse release(UUID taskId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        locks.applyLockTimeout();
        WorkflowTask task = findTask(taskId, tenantId);
        WorkflowStageInstance stage = findStage(task, tenantId);
        WorkflowInstance instance = findInstance(stage, tenantId);
        if (userId.equals(task.getClaimedBy()) && task.getDelegatedTo() != null) {
            throw new WorkflowStateException("WORKFLOW_TASK_DELEGATED",
                    "You delegated this task - it can no longer be released to the pool");
        }
        if (userId.equals(task.getClaimedBy()) && task.getStatus() == TaskStatus.awaiting_info) {
            throw new WorkflowStateException("WORKFLOW_TASK_AWAITING_INFO",
                    "This task is waiting on your information request - withdraw it before releasing the task");
        }
        if (taskRepository.release(taskId, tenantId, userId, OffsetDateTime.now()) == 0) {
            throw new WorkflowStateException("WORKFLOW_TASK_NOT_CLAIMED_BY_YOU",
                    "Only the person who claimed this task can release it");
        }
        actions.record(instance, stage.getStageInstanceId(), taskId, WorkflowActionType.released, userId, null, null);
        taskAudit(AuditActionCatalog.WORKFLOW_TASK_RELEASED, instance, taskId, stage, null, null).log();
        return response(findTask(taskId, tenantId), stage, instance);
    }

    /**
     * §4. Locks instance -> stage instance -> the stage's open tasks (by
     * task_id), re-evaluates every guard under the locks, records this
     * entry's decision, then lets the engine apply the stage's rules.
     */
    @Override
    @Transactional
    public TaskActionResponse act(UUID taskId, TaskDecision decision, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (decision == TaskDecision.reject && cleanReason == null) {
            throw new WorkflowRequestException("WORKFLOW_REASON_REQUIRED", "A reason is required to reject");
        }
        locks.applyLockTimeout();

        // Parent ids as scalars only: nothing may be loaded into the persistence
        // context before its lock, or the FOR UPDATE query returns that stale copy.
        UUID stageInstanceId = taskRepository.findStageInstanceIdOfTask(taskId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId));
        UUID instanceId = stageInstanceRepository.findInstanceIdOfStage(stageInstanceId, tenantId).orElseThrow();
        WorkflowInstance instance = instanceRepository.lockByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        WorkflowStageInstance stage = stageInstanceRepository
                .lockByStageInstanceIdAndTenantId(stageInstanceId, tenantId).orElseThrow();
        List<WorkflowTask> open = taskRepository.lockOpenByStageInstance(stage.getStageInstanceId(), TaskStatus.OPEN);
        WorkflowTask task = open.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst().orElse(null);

        requireActionable(instance, stage, userId);
        if (task == null) {
            WorkflowTask current = taskRepository.findById(taskId).orElseThrow();
            if (TaskStatus.DECIDED.contains(current.getStatus())) {
                throw new WorkflowStateException("WORKFLOW_ENTRY_SATISFIED",
                        "Someone has already decided this entry of the stage");
            }
            throw new WorkflowStateException("WORKFLOW_TASK_CLOSED", "This task is no longer open");
        }
        if (task.getStatus() == TaskStatus.awaiting_info) {
            throw new WorkflowStateException("WORKFLOW_TASK_AWAITING_INFO",
                    "This task is waiting for requested information and can't be decided until every request is closed");
        }
        TaskRouting.Route route = taskRouting.route(tenantId, task, userId);
        if (route == null) {
            if (task.isPool() && task.getClaimedBy() != null && task.getDelegatedTo() == null) {
                throw new WorkflowStateException("WORKFLOW_TASK_ALREADY_CLAIMED",
                        "Another approver has claimed this task");
            }
            throw new WorkflowAccessException("WORKFLOW_NOT_ASSIGNEE", "You are not an approver of this task");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (task.isPool() && task.getClaimedBy() == null) {
            task.setClaimedBy(userId);
            task.setClaimedAt(now);
        }
        boolean approve = decision == TaskDecision.approve;
        task.setStatus(approve ? TaskStatus.approved : TaskStatus.rejected);
        task.setDecision(decision);
        task.setDecisionReason(cleanReason);
        task.setActedBy(userId);
        task.setActedAt(now);
        task.setUpdatedBy(userId);
        task.setUpdatedAt(now);
        try {
            taskRepository.saveAndFlush(task);
        } catch (DataIntegrityViolationException e) {
            // uq_workflow_tasks_one_decision_per_user - the guard above already
            // covers this under the stage lock; kept as the backstop's translation.
            throw new WorkflowStateException("WORKFLOW_ALREADY_ACTED_IN_STAGE", "You have already decided this stage");
        }
        dmsMetrics.recordWorkflowDecision(decision.name());
        if (approve) {
            stage.setApprovedCount(stage.getApprovedCount() + 1);
        } else {
            stage.setRejectedCount(stage.getRejectedCount() + 1);
        }
        stage.setUpdatedBy(userId);
        stage.setUpdatedAt(now);

        Map<String, Object> decisionDetail = new LinkedHashMap<>();
        decisionDetail.put("stageOrder", stage.getStageOrder());
        decisionDetail.put("assigneeType", task.getAssigneeType().name());
        decisionDetail.put("route", route.name());
        if (route == TaskRouting.Route.delegated && task.getDelegatedFrom() != null) {
            // "Approved by <delegate>, delegated from <original>" (decision 2)
            decisionDetail.put("delegatedFrom", task.getDelegatedFrom().toString());
            decisionDetail.put("delegatedFromName", engine.displayName(task.getDelegatedFrom()));
        }
        actions.record(instance, stage.getStageInstanceId(), taskId,
                approve ? WorkflowActionType.approved : WorkflowActionType.rejected, userId, cleanReason,
                decisionDetail);
        taskAudit(approve ? AuditActionCatalog.WORKFLOW_TASK_APPROVED : AuditActionCatalog.WORKFLOW_TASK_REJECTED,
                instance, taskId, stage, task, route).reason(cleanReason).log();

        List<WorkflowTask> remaining = new ArrayList<>(open);
        remaining.remove(task);
        engine.evaluateAndAdvance(instance, stage, remaining, userId);
        return response(task, stage, instance);
    }

    // ---------------------------------------------------------------- guards

    /** Shared by claim and act, in the order that gives each scenario-E refusal its own code. */
    private void requireActionable(WorkflowInstance instance, WorkflowStageInstance stage, UUID userId) {
        // Stage first: a halted or finished workflow has no active stage, and
        // "stage not open" is the more precise answer for a task in it.
        if (stage.getStatus() != StageInstanceStatus.active) {
            throw new WorkflowStateException("WORKFLOW_STAGE_NOT_ACTIVE",
                    "This stage is not open for decisions - it has not been reached yet, or has already closed");
        }
        if (instance.getStatus() != InstanceStatus.in_progress) {
            throw new WorkflowStateException("WORKFLOW_NOT_IN_PROGRESS", "This workflow has already finished");
        }
        if (userId.equals(instance.getInitiatedBy())) {
            throw new WorkflowAccessException("WORKFLOW_SELF_APPROVAL_FORBIDDEN",
                    "You cannot act on a workflow you submitted");
        }
        if (taskRepository.existsByStageInstanceIdAndActedByAndStatusIn(stage.getStageInstanceId(), userId,
                TaskStatus.DECIDED)) {
            throw new WorkflowStateException("WORKFLOW_ALREADY_ACTED_IN_STAGE", "You have already decided this stage");
        }
    }

    private static void requireOpen(WorkflowTask task) {
        if (!TaskStatus.OPEN.contains(task.getStatus())) {
            throw new WorkflowStateException(TaskStatus.DECIDED.contains(task.getStatus()) ? "WORKFLOW_ENTRY_SATISFIED"
                    : "WORKFLOW_TASK_CLOSED", "This task is no longer open");
        }
    }

    // ---------------------------------------------------------------- lookups

    private WorkflowTask findTask(UUID taskId, UUID tenantId) {
        return taskRepository.findByTaskIdAndTenantIdAndDeletedFalse(taskId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId));
    }

    private WorkflowStageInstance findStage(WorkflowTask task, UUID tenantId) {
        return stageInstanceRepository.findByStageInstanceIdAndTenantId(task.getStageInstanceId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowStageInstance", task.getStageInstanceId()));
    }

    private WorkflowInstance findInstance(WorkflowStageInstance stage, UUID tenantId) {
        return instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(stage.getInstanceId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", stage.getInstanceId()));
    }

    private AuditEventBuilder taskAudit(String action, WorkflowInstance instance, UUID taskId,
                                        WorkflowStageInstance stage, WorkflowTask task, TaskRouting.Route route) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskId", taskId);
        detail.put("stageInstanceId", stage.getStageInstanceId());
        detail.put("stageOrder", stage.getStageOrder());
        if (task != null) {
            detail.put("assigneeType", task.getAssigneeType().name());
            detail.put("assigneeId", task.getAssigneeId());
            detail.put("decision", task.getDecision() == null ? null : task.getDecision().name());
            detail.put("delegatedFrom", task.getDelegatedFrom());
            detail.put("delegatedTo", task.getDelegatedTo());
            detail.put("escalatedTo", task.getEscalatedTo());
        }
        if (route != null) {
            detail.put("route", route.name());
        }
        return engine.audit(action, instance)
                .objectType(AuditObjectTypes.WORKFLOW_TASK)
                .objectId(taskId)
                .newValue(engine.instanceSummary(instance, detail));
    }

    private static TaskActionResponse response(WorkflowTask task, WorkflowStageInstance stage, WorkflowInstance instance) {
        return new TaskActionResponse(task.getTaskId(), task.getStatus().name(), stage.getStageInstanceId(),
                stage.getStatus().name(), stage.getApprovedCount(), stage.getRejectedCount(), stage.getRequiredCount(),
                instance.getInstanceId(), instance.getStatus().name(), instance.getCurrentStageInstanceId());
    }
}
