package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.SignAndApproveRequest;
import com.oak.dms.signature.service.DocumentSignatureService;
import com.oak.dms.signature.service.StepUpAuthenticator;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.dto.ReviewLockResponse;
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
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.ApproverResolver;
import com.oak.dms.workflow.service.WorkflowTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
    private final WorkflowStageRepository stageRepository;
    private final ApproverResolver approverResolver;
    private final TaskRouting taskRouting;
    private final WorkflowEngine engine;
    private final WorkflowActionRecorder actions;
    private final WorkflowLocks locks;
    private final DocumentSignatureService signatureService;
    private final com.oak.dms.observability.DmsMetrics dmsMetrics;
    private final StepUpAuthenticator stepUp;
    private final UserRepository userRepository;

    /** How long a review session's soft lock survives without a heartbeat (V120) - long enough to read a document, short enough that an abandoned tab never blocks a stage for long. */
    private static final Duration REVIEW_LOCK_TTL = Duration.ofMinutes(2);

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
        if (decision == TaskDecision.approve && stageRequiresSignature(taskId, tenantId)) {
            throw new WorkflowStateException("WORKFLOW_SIGNATURE_REQUIRED",
                    "This stage requires a digital signature - approve it with sign-and-approve instead of a plain approve.");
        }
        LockedTask locked = lockAndValidate(taskId, tenantId, userId);
        return finishDecision(locked, decision, cleanReason, userId);
    }

    /**
     * 1.4 Digital Signature Integration: signs AND approves in one
     * transaction (decision 1). Locks and validates exactly as {@link #act}
     * does, then signs BEFORE recording any decision - if signing throws
     * (consent/step-up failure, the stored file no longer matching its
     * recorded hash, the key service unreachable, or a failed self-check),
     * nothing below runs and the whole transaction rolls back, so the task
     * is left undecided. Fail closed: there is no path here that records an
     * approval without also having just recorded a valid signature.
     */
    @Override
    @Transactional
    public TaskActionResponse actWithSignature(UUID taskId, SignAndApproveRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        LockedTask locked = lockAndValidate(taskId, tenantId, userId);
        signatureService.sign(locked.instance(), locked.task(), request);
        return finishDecision(locked, TaskDecision.approve, request.comment(), userId);
    }

    /** Whether the stage this task belongs to requires a signature to approve (V105, mirrors allow_info_requests). */
    private boolean stageRequiresSignature(UUID taskId, UUID tenantId) {
        // Scalar queries only: this runs BEFORE lockAndValidate's FOR UPDATE, and loading the stage instance
        // here would put a stale copy in the persistence context (lost approved_count under concurrency).
        return taskRepository.findStageInstanceIdOfTask(taskId, tenantId)
                .flatMap(stageInstanceId -> stageInstanceRepository.findRequiresSignatureOfStage(stageInstanceId, tenantId))
                .orElse(false);
    }

    /** The locks and guards shared by {@link #act} and {@link #actWithSignature}, before either records a decision. */
    private record LockedTask(WorkflowInstance instance, WorkflowStageInstance stage, WorkflowTask task,
                              TaskRouting.Route route, List<WorkflowTask> open) {
    }

    private LockedTask lockAndValidate(UUID taskId, UUID tenantId, UUID userId) {
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
        return new LockedTask(instance, stage, task, route, open);
    }

    /** Records this entry's decision and lets the engine apply the stage's rules - shared by {@link #act} and {@link #actWithSignature}. */
    private TaskActionResponse finishDecision(LockedTask locked, TaskDecision decision, String reason, UUID userId) {
        WorkflowInstance instance = locked.instance();
        WorkflowStageInstance stage = locked.stage();
        WorkflowTask task = locked.task();
        TaskRouting.Route route = locked.route();
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (decision == TaskDecision.reject && cleanReason == null) {
            throw new WorkflowRequestException("WORKFLOW_REASON_REQUIRED", "A reason is required to reject");
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
        actions.record(instance, stage.getStageInstanceId(), task.getTaskId(),
                approve ? WorkflowActionType.approved : WorkflowActionType.rejected, userId, cleanReason,
                decisionDetail);
        taskAudit(approve ? AuditActionCatalog.WORKFLOW_TASK_APPROVED : AuditActionCatalog.WORKFLOW_TASK_REJECTED,
                instance, task.getTaskId(), stage, task, route).reason(cleanReason).log();

        List<WorkflowTask> remaining = new ArrayList<>(locked.open());
        remaining.remove(task);
        engine.evaluateAndAdvance(instance, stage, remaining, userId);
        return response(task, stage, instance);
    }

    // ------------------------------------------------------------ retraction

    /**
     * V120: re-proves it's really the signer (same step-up ceremony signing itself depends on) before
     * even showing them their own past decision again, then acquires the soft review lock on the whole
     * stage - every OTHER entry is refused (see {@link #requireActionable}'s own check) until this
     * session ends (close or retract) or the lock's TTL lapses on its own. Deliberately NOT a real row
     * lock: that can't be held open across however many separate HTTP requests a signer's browsing takes,
     * and the real safety net for the retraction itself is still the pessimistic instance/stage/task lock
     * {@link #retract} takes - this lock only ever protects against another approver racing ahead while
     * someone is *looking*, nothing more.
     */
    @Override
    @Transactional
    public ReviewLockResponse openForReview(UUID taskId, String password, String totp) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        WorkflowTask task = findTask(taskId, tenantId);
        WorkflowStageInstance stage = findStage(task, tenantId);
        WorkflowInstance instance = findInstance(stage, tenantId);
        requireOwnRetractableDecision(task, userId);
        requireStageStillOpenForReview(stage);

        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        stepUp.verify(user, password, totp);

        acquireOrRenewReviewLock(stage, userId);
        return reviewResponse(task, instance, stage);
    }

    @Override
    @Transactional
    public ReviewLockResponse renewReviewLock(UUID taskId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        WorkflowTask task = findTask(taskId, tenantId);
        WorkflowStageInstance stage = findStage(task, tenantId);
        WorkflowInstance instance = findInstance(stage, tenantId);
        if (!userId.equals(stage.getReviewLockedBy())) {
            throw new WorkflowStateException("WORKFLOW_REVIEW_SESSION_ENDED",
                    "This review session is no longer active - it may have expired or been closed");
        }
        acquireOrRenewReviewLock(stage, userId);
        return reviewResponse(task, instance, stage);
    }

    @Override
    @Transactional
    public void closeReview(UUID taskId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        WorkflowTask task = findTask(taskId, tenantId);
        WorkflowStageInstance stage = findStage(task, tenantId);
        if (userId.equals(stage.getReviewLockedBy())) {
            clearReviewLock(stage);
            stageInstanceRepository.save(stage);
        }
    }

    /**
     * Retracts the caller's own approval - locked and re-validated exactly like {@link #act} (instance,
     * then stage instance, then this task's row), so the stage's completion state can never shift out
     * from under this decision mid-flight. Never erases the original decision/decisionReason/actedBy/
     * actedAt in place - they're simply overwritten once the signer re-decides, same as any other field
     * update; the full before/after lives in the audit log (WORKFLOW_TASK_RETRACTED), not in these
     * columns, which only ever mark "this task has been retracted before" for quick display.
     */
    @Override
    @Transactional
    public TaskActionResponse retract(UUID taskId, String reason, String password, String totp) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (cleanReason == null) {
            throw new WorkflowRequestException("WORKFLOW_REASON_REQUIRED", "A reason is required to retract your decision");
        }

        locks.applyLockTimeout();
        UUID stageInstanceId = taskRepository.findStageInstanceIdOfTask(taskId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId));
        UUID instanceId = stageInstanceRepository.findInstanceIdOfStage(stageInstanceId, tenantId).orElseThrow();
        WorkflowInstance instance = instanceRepository.lockByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        WorkflowStageInstance stage = stageInstanceRepository
                .lockByStageInstanceIdAndTenantId(stageInstanceId, tenantId).orElseThrow();
        WorkflowTask task = taskRepository.lockByTaskIdAndTenantId(taskId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId));

        requireOwnRetractableDecision(task, userId);
        requireStageStillOpenForReview(stage);

        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        stepUp.verify(user, password, totp);

        TaskDecision previousDecision = task.getDecision();
        String previousReason = task.getDecisionReason();
        OffsetDateTime previousActedAt = task.getActedAt();

        UUID signedVersionId = signatureService.invalidateForTaskRetraction(taskId,
                "Approval retracted: " + cleanReason);
        if (signedVersionId != null && task.getSignaturePlacementBaseVersionId() != null) {
            instanceRepository.advanceDocumentVersion(instance.getInstanceId(), tenantId,
                    task.getSignaturePlacementBaseVersionId());
        }

        OffsetDateTime now = OffsetDateTime.now();
        stage.setApprovedCount(Math.max(0, stage.getApprovedCount() - 1));
        clearReviewLock(stage);
        stage.setUpdatedBy(userId);
        stage.setUpdatedAt(now);
        stageInstanceRepository.save(stage);

        task.setStatus(task.isPool() ? TaskStatus.claimed : TaskStatus.pending);
        task.setDecision(null);
        task.setDecisionReason(null);
        task.setActedBy(null);
        task.setActedAt(null);
        task.setRetractedAt(now);
        task.setRetractedBy(userId);
        task.setRetractionReason(cleanReason);
        task.setUpdatedBy(userId);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stageOrder", stage.getStageOrder());
        detail.put("previousDecision", previousDecision == null ? null : previousDecision.name());
        detail.put("previousReason", previousReason);
        detail.put("previousActedAt", previousActedAt == null ? null : previousActedAt.toString());
        detail.put("signatureInvalidated", signedVersionId != null);
        actions.record(instance, stage.getStageInstanceId(), task.getTaskId(), WorkflowActionType.retracted,
                userId, cleanReason, detail);
        engine.audit(AuditActionCatalog.WORKFLOW_TASK_RETRACTED, instance)
                .objectType(AuditObjectTypes.WORKFLOW_TASK)
                .objectId(task.getTaskId())
                .reason(cleanReason)
                .previousValue(detail)
                .newValue(engine.instanceSummary(instance, Map.of("taskId", task.getTaskId(), "stageOrder",
                        stage.getStageOrder())))
                .log();

        return response(task, stage, instance);
    }

    private static void requireOwnRetractableDecision(WorkflowTask task, UUID userId) {
        if (!userId.equals(task.getActedBy()) || task.getDecision() != TaskDecision.approve) {
            throw new WorkflowAccessException("WORKFLOW_NOT_YOUR_DECISION",
                    "You can only review or retract your own approval");
        }
    }

    /** 409 once the stage has closed - a later decision (the completion rule being satisfied, by someone else or this same stage rejecting) means there's nothing left to retract into. */
    private static void requireStageStillOpenForReview(WorkflowStageInstance stage) {
        if (stage.getStatus() != StageInstanceStatus.active) {
            throw new WorkflowStateException("WORKFLOW_RETRACTION_WINDOW_CLOSED",
                    "This can no longer be reviewed - a later decision has already been recorded for this stage");
        }
    }

    private static void acquireOrRenewReviewLock(WorkflowStageInstance stage, UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        if (stage.getReviewLockedBy() != null && !stage.getReviewLockedBy().equals(userId)
                && stage.getReviewLockExpiresAt() != null && stage.getReviewLockExpiresAt().isAfter(now)) {
            throw new WorkflowStateException("WORKFLOW_STAGE_UNDER_REVIEW",
                    "Another approver is currently reviewing a decision on this stage - try again shortly");
        }
        stage.setReviewLockedBy(userId);
        stage.setReviewLockedAt(now);
        stage.setReviewLockExpiresAt(now.plus(REVIEW_LOCK_TTL));
    }

    private static void clearReviewLock(WorkflowStageInstance stage) {
        stage.setReviewLockedBy(null);
        stage.setReviewLockedAt(null);
        stage.setReviewLockExpiresAt(null);
    }

    private static ReviewLockResponse reviewResponse(WorkflowTask task, WorkflowInstance instance, WorkflowStageInstance stage) {
        return new ReviewLockResponse(task.getTaskId(), stage.getStageInstanceId(), instance.getDocumentId(),
                instance.getDocumentVersionId(), task.getDecision() == null ? null : task.getDecision().name(),
                task.getDecisionReason(), task.getActedAt(), stage.getReviewLockExpiresAt());
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
        if (stage.getReviewLockedBy() != null && !stage.getReviewLockedBy().equals(userId)
                && stage.getReviewLockExpiresAt() != null && stage.getReviewLockExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new WorkflowStateException("WORKFLOW_STAGE_UNDER_REVIEW",
                    "Another approver is currently reviewing a decision on this stage - try again shortly");
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
