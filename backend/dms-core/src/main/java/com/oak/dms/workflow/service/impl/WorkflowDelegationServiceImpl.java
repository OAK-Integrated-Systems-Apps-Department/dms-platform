package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.dto.PersonOptionResponse;
import com.oak.dms.workflow.dto.TaskActionResponse;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.StageInstanceStatus;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.event.WorkflowTaskDelegatedEvent;
import com.oak.dms.workflow.exception.WorkflowAccessException;
import com.oak.dms.workflow.exception.WorkflowRequestException;
import com.oak.dms.workflow.exception.WorkflowStateException;
import com.oak.dms.workflow.exception.WorkflowValidationException;
import com.oak.dms.workflow.helper.TaskLocker;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.WorkflowDelegationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delegation and admin reassignment (Phase 2 plan §5). Both change the
 * holder of the SAME task in place - never a new task, so the one-task-per-
 * entry constraint and per-entry counting stay valid (decision 1).
 * Locks: instance, stage instance, task ({@link TaskLocker}).
 */
@Service
@RequiredArgsConstructor
public class WorkflowDelegationServiceImpl implements WorkflowDelegationService {

    private static final int MAX_CANDIDATES = 50;

    private final TaskLocker taskLocker;
    private final TaskRouting taskRouting;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final UserPermissions userPermissions;
    private final AuthorizationService authorizationService;
    private final WorkflowActionRecorder actions;
    private final WorkflowEngine engine;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public TaskActionResponse delegate(UUID taskId, UUID delegateUserId, String note) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        TaskLocker.Locked locked = taskLocker.lock(taskId, tenantId);
        WorkflowInstance instance = locked.instance();
        WorkflowStageInstance stage = locked.stage();
        WorkflowTask task = locked.task();

        requireRunning(instance, stage);
        requireDecidable(task);
        UUID holder = TaskRouting.holder(task);
        if (holder == null) {
            throw new WorkflowStateException("WORKFLOW_TASK_NOT_CLAIMED",
                    "Claim this pool task before delegating it - an unclaimed task belongs to the whole pool");
        }
        if (!userId.equals(holder)) {
            throw new WorkflowAccessException("WORKFLOW_NOT_TASK_HOLDER", "Only the person holding this task can delegate it");
        }
        requireEligibleTarget(tenantId, instance, stage, delegateUserId, userId);

        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        OffsetDateTime now = OffsetDateTime.now();
        boolean handedBack = delegateUserId.equals(task.getDelegatedFrom());
        UUID originalHolder = task.getDelegatedFrom() != null ? task.getDelegatedFrom() : userId;
        if (handedBack) {
            task.setDelegatedFrom(null);
            task.setDelegatedTo(null);
            task.setDelegatedAt(null);
        } else {
            task.setDelegatedFrom(originalHolder);
            task.setDelegatedTo(delegateUserId);
            task.setDelegatedAt(now);
        }
        task.setLastActivityAt(now);
        task.setUpdatedBy(userId);
        task.setUpdatedAt(now);
        taskRepository.saveAndFlush(task);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stageOrder", stage.getStageOrder());
        detail.put("from", userId.toString());
        detail.put("fromName", engine.displayName(userId));
        detail.put("to", delegateUserId.toString());
        detail.put("toName", engine.displayName(delegateUserId));
        detail.put("originalHolder", originalHolder.toString());
        detail.put("returnedToOriginal", handedBack);
        actions.record(instance, stage.getStageInstanceId(), taskId, WorkflowActionType.delegated, userId, cleanNote,
                detail);
        audit(AuditActionCatalog.WORKFLOW_TASK_DELEGATED, instance, stage, task, detail).log();
        eventPublisher.publishEvent(new WorkflowTaskDelegatedEvent(tenantId, instance.getInstanceId(), taskId,
                "delegated", userId, delegateUserId, userId, cleanNote));
        return response(task, stage, instance);
    }

    @Override
    @Transactional
    public TaskActionResponse reassign(UUID taskId, UUID targetUserId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (cleanReason == null) {
            throw new WorkflowRequestException("WORKFLOW_REASON_REQUIRED", "A reason is required to reassign a task");
        }
        TaskLocker.Locked locked = taskLocker.lock(taskId, tenantId);
        WorkflowInstance instance = locked.instance();
        WorkflowStageInstance stage = locked.stage();
        WorkflowTask task = locked.task();

        requireRunning(instance, stage);
        requireOpen(task);
        UUID previousHolder = TaskRouting.holder(task);
        if (targetUserId.equals(previousHolder)) {
            throw new WorkflowValidationException("WORKFLOW_INVALID_DELEGATE", "This person already holds the task");
        }
        requireEligibleTarget(tenantId, instance, stage, targetUserId, null);
        if (task.getStatus() == TaskStatus.awaiting_info) {
            engine.closeInfoRequestsForReassignment(instance, stage, task, userId);
        }

        OffsetDateTime now = OffsetDateTime.now();
        UUID originalHolder = task.getDelegatedFrom() != null ? task.getDelegatedFrom() : previousHolder;
        if (targetUserId.equals(originalHolder)) {
            task.setDelegatedFrom(null);
            task.setDelegatedTo(null);
            task.setDelegatedAt(null);
        } else {
            task.setDelegatedFrom(originalHolder);
            task.setDelegatedTo(targetUserId);
            task.setDelegatedAt(now);
        }
        task.setLastActivityAt(now);
        task.setUpdatedBy(userId);
        task.setUpdatedAt(now);
        taskRepository.saveAndFlush(task);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stageOrder", stage.getStageOrder());
        detail.put("from", previousHolder == null ? null : previousHolder.toString());
        detail.put("fromName", engine.displayName(previousHolder));
        detail.put("to", targetUserId.toString());
        detail.put("toName", engine.displayName(targetUserId));
        detail.put("originalHolder", originalHolder == null ? null : originalHolder.toString());
        actions.record(instance, stage.getStageInstanceId(), taskId, WorkflowActionType.reassigned, userId,
                cleanReason, detail);
        audit(AuditActionCatalog.WORKFLOW_TASK_REASSIGNED, instance, stage, task, detail).reason(cleanReason).log();
        eventPublisher.publishEvent(new WorkflowTaskDelegatedEvent(tenantId, instance.getInstanceId(), taskId,
                "reassigned", previousHolder, targetUserId, userId, cleanReason));
        return response(task, stage, instance);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PersonOptionResponse> delegateCandidates(UUID taskId, String search, int limit) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        WorkflowTask task = taskRepository.findByTaskIdAndTenantIdAndDeletedFalse(taskId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId));
        WorkflowStageInstance stage = stageInstanceRepository.findByStageInstanceIdAndTenantId(
                task.getStageInstanceId(), tenantId).orElseThrow();
        WorkflowInstance instance = instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(
                stage.getInstanceId(), tenantId).orElseThrow();
        boolean admin = authorizationService.hasPermission(PermissionCodes.WA_TASK_REASSIGN);
        if (!admin && !userId.equals(TaskRouting.holder(task))) {
            throw new WorkflowAccessException("WORKFLOW_NOT_TASK_HOLDER", "Only the person holding this task can delegate it");
        }

        String pattern = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%";
        List<User> users = userRepository.searchActive(tenantId, pattern,
                PageRequest.of(0, Math.max(1, Math.min(limit, MAX_CANDIDATES)) * 2));
        UUID holder = TaskRouting.holder(task);
        Set<UUID> eligible = userPermissions.filterHoldingAny(tenantId, users.stream().map(User::getUserId).toList(),
                PermissionCodes.WA_TASK_APPROVE, PermissionCodes.WA_TASK_REJECT);
        Map<UUID, String> departmentNames = new HashMap<>();
        departmentRepository.findByDepartmentIdInAndTenantIdAndDeletedFalse(
                        users.stream().map(User::getDepartmentId).filter(id -> id != null).toList(), tenantId)
                .forEach((Department d) -> departmentNames.put(d.getDepartmentId(), d.getName()));
        return users.stream()
                .filter(u -> eligible.contains(u.getUserId()))
                .filter(u -> !u.getUserId().equals(userId) || admin)
                .filter(u -> !u.getUserId().equals(holder))
                .filter(u -> !u.getUserId().equals(instance.getInitiatedBy()))
                .filter(u -> !decidedInStage(stage, u.getUserId()))
                .limit(Math.max(1, Math.min(limit, MAX_CANDIDATES)))
                .map(u -> new PersonOptionResponse(u.getUserId(), u.getDisplayName(), u.getUsername(),
                        departmentNames.get(u.getDepartmentId()), null))
                .toList();
    }

    // ---------------------------------------------------------------- guards

    private static void requireRunning(WorkflowInstance instance, WorkflowStageInstance stage) {
        if (stage.getStatus() != StageInstanceStatus.active) {
            throw new WorkflowStateException("WORKFLOW_STAGE_NOT_ACTIVE",
                    "This stage is not open - it has not been reached yet, or has already closed");
        }
        if (instance.getStatus() != InstanceStatus.in_progress) {
            throw new WorkflowStateException("WORKFLOW_NOT_IN_PROGRESS", "This workflow has already finished");
        }
    }

    private static void requireOpen(WorkflowTask task) {
        if (!TaskStatus.OPEN.contains(task.getStatus())) {
            throw new WorkflowStateException(TaskStatus.DECIDED.contains(task.getStatus())
                    ? "WORKFLOW_ENTRY_SATISFIED" : "WORKFLOW_TASK_CLOSED", "This task is no longer open");
        }
    }

    private static void requireDecidable(WorkflowTask task) {
        requireOpen(task);
        if (task.getStatus() == TaskStatus.awaiting_info) {
            throw new WorkflowStateException("WORKFLOW_TASK_AWAITING_INFO",
                    "This task is waiting for requested information - withdraw your requests before delegating it");
        }
    }

    /**
     * Decision 3 and §5: an active user of the tenant, not the caller, not
     * the initiator (segregation of duties), not someone who already decided
     * in this stage (uq_workflow_tasks_one_decision_per_user), and holding
     * an approve or reject permission - otherwise the task would stall.
     */
    private void requireEligibleTarget(UUID tenantId, WorkflowInstance instance, WorkflowStageInstance stage,
                                       UUID targetUserId, UUID callerId) {
        User target = userRepository.findByUserIdAndTenantIdAndDeletedFalse(targetUserId, tenantId)
                .filter(u -> u.getStatus() == User.UserStatus.active)
                .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_INVALID_DELEGATE",
                        "The selected person does not exist or is not active"));
        if (target.getUserId().equals(callerId)) {
            throw new WorkflowValidationException("WORKFLOW_INVALID_DELEGATE", "You can't delegate a task to yourself");
        }
        if (target.getUserId().equals(instance.getInitiatedBy())) {
            throw new WorkflowValidationException("WORKFLOW_DELEGATE_IS_INITIATOR",
                    target.getDisplayName() + " submitted this workflow and can't approve it");
        }
        if (decidedInStage(stage, target.getUserId())) {
            throw new WorkflowValidationException("WORKFLOW_DELEGATE_ALREADY_ACTED",
                    target.getDisplayName() + " has already decided in this stage and can't take another task in it");
        }
        if (!userPermissions.holdsAny(tenantId, target.getUserId(), PermissionCodes.WA_TASK_APPROVE,
                PermissionCodes.WA_TASK_REJECT)) {
            throw new WorkflowValidationException("WORKFLOW_DELEGATE_LACKS_PERMISSION",
                    target.getDisplayName() + " doesn't have permission to approve or reject workflow tasks");
        }
    }

    private boolean decidedInStage(WorkflowStageInstance stage, UUID userId) {
        return taskRepository.existsByStageInstanceIdAndActedByAndStatusIn(stage.getStageInstanceId(), userId,
                TaskStatus.DECIDED);
    }

    // ---------------------------------------------------------------- helpers

    private com.oak.dms.audit.helper.AuditEventBuilder audit(String action, WorkflowInstance instance,
                                                            WorkflowStageInstance stage, WorkflowTask task,
                                                            Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>(extra);
        detail.put("taskId", task.getTaskId());
        detail.put("stageInstanceId", stage.getStageInstanceId());
        detail.put("assigneeType", task.getAssigneeType().name());
        detail.put("assigneeId", task.getAssigneeId());
        detail.put("delegatedFrom", task.getDelegatedFrom());
        detail.put("delegatedTo", task.getDelegatedTo());
        return engine.audit(action, instance)
                .objectType(AuditObjectTypes.WORKFLOW_TASK)
                .objectId(task.getTaskId())
                .newValue(engine.instanceSummary(instance, detail));
    }

    private static TaskActionResponse response(WorkflowTask task, WorkflowStageInstance stage, WorkflowInstance instance) {
        return new TaskActionResponse(task.getTaskId(), task.getStatus().name(), stage.getStageInstanceId(),
                stage.getStatus().name(), stage.getApprovedCount(), stage.getRejectedCount(), stage.getRequiredCount(),
                instance.getInstanceId(), instance.getStatus().name(), instance.getCurrentStageInstanceId());
    }
}
