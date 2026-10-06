package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.entity.WorkflowInfoRequest;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowStageParticipant;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.InfoRequestStatus;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.StageInstanceStatus;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.event.WorkflowInfoRequestEvent;
import com.oak.dms.workflow.event.WorkflowOutcomeEvent;
import com.oak.dms.workflow.event.WorkflowStageUnassignableEvent;
import com.oak.dms.workflow.event.WorkflowTaskAssignedEvent;
import com.oak.dms.workflow.exception.WorkflowValidationException;
import com.oak.dms.workflow.helper.StageOutcomeEvaluator;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.repository.WorkflowInfoRequestRepository;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageParticipantRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.ApproverResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The state transitions shared by submission, approve/reject, cancel and
 * version-change handling (docs/workflow-phase1-plan.md §3-§5). Every
 * method requires the caller's transaction AND the caller already holding
 * the lock on the instance (and, where it touches one, on the stage
 * instance) - lock order documents -> instances -> stage instances -> tasks.
 * <p>
 * Explicit flushes are deliberate: Hibernate otherwise runs inserts before
 * updates, and orders updates by id, which would trip the non-deferrable
 * partial unique indexes (one active stage per instance, one in_progress
 * instance per document) mid-transition.
 */
@Component
@RequiredArgsConstructor
public class WorkflowEngine {

    private static final int MAX_REASON = 2000;

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowStageParticipantRepository participantRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowInfoRequestRepository infoRequestRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final ApproverResolver approverResolver;
    private final WorkflowActionRecorder actions;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * not_started -> active: one pending task per participant entry, counters
     * set from the completion rule, SLA due date stamped. An entry whose pool
     * has nobody eligible still gets its task - parked, recorded as
     * stage_unassignable and mailed to admins (plan §5).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<WorkflowTask> activateStage(WorkflowInstance instance, WorkflowStageInstance stageInstance,
                                            UUID actorUserId) {
        UUID tenantId = instance.getTenantId();
        WorkflowStage stage = stageRepository.findById(stageInstance.getStageId())
                .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_TEMPLATE_INVALID",
                        "A stage of this workflow's template no longer exists"));
        List<WorkflowStageParticipant> participants = participantRepository
                .findByStageIdAndTenantIdAndDeletedFalseOrderByDisplayOrderAscCreatedAtAsc(stage.getStageId(), tenantId);
        if (participants.isEmpty()) {
            throw new WorkflowValidationException("WORKFLOW_TEMPLATE_INVALID",
                    "Stage \"" + stage.getName() + "\" has no approvers");
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime dueAt = stage.getSlaHours() == null ? null : now.plusHours(stage.getSlaHours());
        int n = participants.size();
        stageInstance.setStatus(StageInstanceStatus.active);
        stageInstance.setEntryCount(n);
        stageInstance.setRequiredCount(StageOutcomeEvaluator.requiredCount(stage.getCompletionRule(), n));
        stageInstance.setApprovedCount(0);
        stageInstance.setRejectedCount(0);
        stageInstance.setActivatedAt(now);
        stageInstance.setDueAt(dueAt);
        stageInstance.setUpdatedBy(actorUserId);
        stageInstance.setUpdatedAt(now);
        stageInstanceRepository.saveAndFlush(stageInstance);

        List<WorkflowTask> tasks = new ArrayList<>();
        for (WorkflowStageParticipant p : participants) {
            tasks.add(WorkflowTask.builder()
                    .tenantId(tenantId)
                    .stageInstanceId(stageInstance.getStageInstanceId())
                    .participantId(p.getParticipantId())
                    .assigneeType(p.getAssigneeType())
                    .assigneeId(p.getAssigneeId())
                    .status(TaskStatus.pending)
                    .dueAt(dueAt)
                    .createdBy(actorUserId)
                    .build());
        }
        tasks = taskRepository.saveAllAndFlush(tasks);

        instance.setCurrentStageInstanceId(stageInstance.getStageInstanceId());
        instance.setUpdatedBy(actorUserId);
        instance.setUpdatedAt(now);
        instanceRepository.save(instance);

        List<Map<String, Object>> entries = new ArrayList<>();
        List<UUID> unassignable = new ArrayList<>();
        for (int i = 0; i < participants.size(); i++) {
            WorkflowStageParticipant p = participants.get(i);
            WorkflowTask task = tasks.get(i);
            Set<UUID> resolved = approverResolver.resolve(tenantId, p, instance.getInitiatedBy());
            if (resolved.isEmpty()) {
                unassignable.add(task.getTaskId());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("participantId", p.getParticipantId());
            entry.put("assigneeType", p.getAssigneeType().name());
            entry.put("assigneeId", p.getAssigneeId());
            entry.put("taskId", task.getTaskId());
            entry.put("resolvedUserIds", resolved.stream().map(UUID::toString).toList());
            entries.add(entry);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stageOrder", stageInstance.getStageOrder());
        detail.put("stageName", stage.getName());
        detail.put("requiredCount", stageInstance.getRequiredCount());
        detail.put("entries", entries);
        actions.record(instance, stageInstance.getStageInstanceId(), null, WorkflowActionType.stage_activated,
                null, null, detail);

        if (!unassignable.isEmpty()) {
            actions.record(instance, stageInstance.getStageInstanceId(), null, WorkflowActionType.stage_unassignable,
                    null, null, Map.of("taskIds", unassignable.stream().map(UUID::toString).toList()));
            eventPublisher.publishEvent(new WorkflowStageUnassignableEvent(tenantId, instance.getInstanceId(),
                    stageInstance.getStageInstanceId(), unassignable));
        }
        eventPublisher.publishEvent(new WorkflowTaskAssignedEvent(tenantId, instance.getInstanceId(),
                tasks.stream().map(WorkflowTask::getTaskId).toList()));
        return tasks;
    }

    /**
     * Applies the stage's rules after one entry decided (§4) and, if the
     * stage concluded, closes it: remaining open tasks cancelled, then the
     * next stage activated, or the instance completed.
     *
     * @param remainingOpen the stage's other open tasks, already locked
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StageOutcomeEvaluator.Outcome evaluateAndAdvance(WorkflowInstance instance,
                                                            WorkflowStageInstance stageInstance,
                                                            List<WorkflowTask> remainingOpen, UUID actorUserId) {
        WorkflowStage stage = stageRepository.findById(stageInstance.getStageId()).orElseThrow();
        StageOutcomeEvaluator.Outcome outcome = StageOutcomeEvaluator.evaluate(stage.getRejectionRule(),
                stageInstance.getEntryCount(), stageInstance.getRequiredCount(), stageInstance.getApprovedCount(),
                stageInstance.getRejectedCount());
        switch (outcome) {
            case STILL_OPEN -> stageInstanceRepository.save(stageInstance);
            case APPROVED -> approveStage(instance, stageInstance, stage, remainingOpen, actorUserId);
            case REJECTED -> rejectStage(instance, stageInstance, stage, remainingOpen, actorUserId);
        }
        return outcome;
    }

    /**
     * in_progress -> cancelled (withdrawal, or a version-change restart):
     * the current stage's open tasks are cancelled, the active and
     * not-started stages skipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(WorkflowInstance instance, String reason, UUID actorUserId, WorkflowActionType actionType,
                       Map<String, Object> detail) {
        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> cancelled = new ArrayList<>();
        if (instance.getCurrentStageInstanceId() != null) {
            WorkflowStageInstance current = stageInstanceRepository
                    .lockByStageInstanceIdAndTenantId(instance.getCurrentStageInstanceId(), instance.getTenantId())
                    .orElseThrow();
            List<WorkflowTask> open = taskRepository.lockOpenByStageInstance(current.getStageInstanceId(), TaskStatus.OPEN);
            String cause = detail != null && "document_version_changed".equals(detail.get("cause"))
                    ? CAUSE_RESTARTED : CAUSE_WITHDRAWN;
            cancelled.addAll(cancelTasks(instance, open, cause, actorUserId, now));
        }
        skipRemainingStages(instance, actorUserId, now);

        instance.setStatus(InstanceStatus.cancelled);
        instance.setCompletedAt(now);
        instance.setOutcomeReason(truncate(reason));
        instance.setCurrentStageInstanceId(null);
        instance.setUpdatedBy(actorUserId);
        instance.setUpdatedAt(now);
        instanceRepository.saveAndFlush(instance);

        Map<String, Object> actionDetail = new HashMap<>(detail == null ? Map.of() : detail);
        actionDetail.put("cancelledTaskIds", cancelled.stream().map(UUID::toString).toList());
        actions.record(instance, null, null, actionType, actorUserId, reason, actionDetail);
        audit(AuditActionCatalog.WORKFLOW_CANCELLED, instance)
                .reason(reason)
                .newValue(instanceSummary(instance, actionDetail))
                .log();
        eventPublisher.publishEvent(new WorkflowOutcomeEvent(instance.getTenantId(), instance.getInstanceId(),
                actorUserId));
    }

    // ---------------------------------------------------------------- information requests

    /** Why open information requests were cancelled by the engine (workflow_info_requests.cancel_reason). */
    public static final String CAUSE_STAGE_APPROVED = "stage_approved";
    public static final String CAUSE_WORKFLOW_REJECTED = "workflow_rejected";
    public static final String CAUSE_WITHDRAWN = "withdrawn";
    public static final String CAUSE_RESTARTED = "restarted";
    public static final String CAUSE_TASK_REASSIGNED = "task_reassigned";

    private static final Map<String, String> CAUSE_TEXT = Map.of(
            CAUSE_STAGE_APPROVED, "The stage was approved without this approver",
            CAUSE_WORKFLOW_REJECTED, "The workflow was rejected",
            CAUSE_WITHDRAWN, "The workflow was withdrawn",
            CAUSE_RESTARTED, "The workflow restarted on a new document version",
            CAUSE_TASK_REASSIGNED, "The task was reassigned to someone else");

    /**
     * An admin reassigns a task that is waiting on information: its open
     * requests are cancelled and the task resumes (the pause is added to
     * due_at) before it moves. The task is already locked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeInfoRequestsForReassignment(WorkflowInstance instance, WorkflowStageInstance stageInstance,
                                                 WorkflowTask task, UUID actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        cancelOpenRequests(instance, List.of(task), CAUSE_TASK_REASSIGNED, actorUserId, now);
        resume(task, now);
        taskRepository.saveAndFlush(task);
    }

    /**
     * Last open request closed: the paused time is added to due_at so the
     * approver keeps their full SLA (decision 14), and the task goes back to
     * claimed (it has a holder) or pending (a user task). Doesn't save.
     */
    public void resume(WorkflowTask task, OffsetDateTime now) {
        if (task.getSlaPausedAt() != null) {
            long paused = Math.max(0, java.time.Duration.between(task.getSlaPausedAt(), now).getSeconds());
            task.setSlaPausedSeconds(task.getSlaPausedSeconds() + (int) paused);
            if (task.getDueAt() != null) {
                task.setDueAt(task.getDueAt().plusSeconds(paused));
            }
            task.setSlaPausedAt(null);
        }
        task.setStatus(task.getClaimedBy() != null ? TaskStatus.claimed : TaskStatus.pending);
        task.setUpdatedAt(now);
    }

    private static void foldPause(WorkflowTask task, OffsetDateTime now) {
        if (task.getSlaPausedAt() != null) {
            long paused = Math.max(0, java.time.Duration.between(task.getSlaPausedAt(), now).getSeconds());
            task.setSlaPausedSeconds(task.getSlaPausedSeconds() + (int) paused);
            task.setSlaPausedAt(null);
        }
    }

    private void cancelOpenRequests(WorkflowInstance instance, List<WorkflowTask> tasks, String cause,
                                    UUID actorUserId, OffsetDateTime now) {
        if (tasks.isEmpty()) {
            return;
        }
        List<WorkflowInfoRequest> open = infoRequestRepository.lockOpenByTaskIds(instance.getTenantId(),
                tasks.stream().map(WorkflowTask::getTaskId).toList());
        if (open.isEmpty()) {
            return;
        }
        String reason = CAUSE_TEXT.getOrDefault(cause, cause);
        for (WorkflowInfoRequest r : open) {
            r.setStatus(InfoRequestStatus.cancelled);
            r.setCancelReason(reason);
            r.setUpdatedBy(actorUserId);
            r.setUpdatedAt(now);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("cause", cause);
            detail.put("automatic", true);
            detail.put("requestedFrom", r.getRequestedFrom().toString());
            actions.record(instance, r.getStageInstanceId(), r.getTaskId(), r.getInfoRequestId(),
                    WorkflowActionType.info_cancelled, null, reason, detail);
            infoRequestAudit(AuditActionCatalog.WORKFLOW_INFO_CANCELLED, instance, r, detail).reason(reason).log();
        }
        infoRequestRepository.saveAllAndFlush(open);
        eventPublisher.publishEvent(new WorkflowInfoRequestEvent(instance.getTenantId(), instance.getInstanceId(),
                open.stream().map(WorkflowInfoRequest::getInfoRequestId).toList(), "cancelled", actorUserId, null));
    }

    /** Audit builder for an information-request event (object WORKFLOW_INFO_REQUEST). */
    public AuditEventBuilder infoRequestAudit(String action, WorkflowInstance instance, WorkflowInfoRequest request,
                                              Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("infoRequestId", request.getInfoRequestId());
        detail.put("taskId", request.getTaskId());
        detail.put("stageInstanceId", request.getStageInstanceId());
        detail.put("requestedBy", request.getRequestedBy());
        detail.put("requestedFrom", request.getRequestedFrom());
        detail.put("requestStatus", request.getStatus().name());
        if (extra != null) {
            detail.putAll(extra);
        }
        return audit(action, instance)
                .objectType(AuditObjectTypes.WORKFLOW_INFO_REQUEST)
                .objectId(request.getInfoRequestId())
                .newValue(instanceSummary(instance, detail));
    }

    /** The audit builder every workflow-instance event starts from - tenant set explicitly for listener contexts. */
    public AuditEventBuilder audit(String action, WorkflowInstance instance) {
        String title = documentRepository.findById(instance.getDocumentId()).map(Document::getTitle).orElse(null);
        return auditService.event(action)
                .tenantId(instance.getTenantId())
                .objectType(AuditObjectTypes.WORKFLOW_INSTANCE)
                .objectId(instance.getInstanceId())
                .resourceName(title)
                .objectVersion(instance.getTemplateVersionNumber())
                .outcome(AuditOutcome.SUCCESS);
    }

    public Map<String, Object> instanceSummary(WorkflowInstance instance, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", instance.getInstanceId());
        m.put("documentId", instance.getDocumentId());
        m.put("documentVersionId", instance.getDocumentVersionId());
        m.put("templateId", instance.getTemplateId());
        m.put("templateVersionNumber", instance.getTemplateVersionNumber());
        m.put("status", instance.getStatus().name());
        if (extra != null) {
            m.putAll(extra);
        }
        return m;
    }

    // ---------------------------------------------------------------- internals

    private void approveStage(WorkflowInstance instance, WorkflowStageInstance stageInstance, WorkflowStage stage,
                              List<WorkflowTask> remainingOpen, UUID actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> cancelled = cancelTasks(instance, remainingOpen, CAUSE_STAGE_APPROVED, actorUserId, now);
        closeStage(stageInstance, StageInstanceStatus.approved, actorUserId, now);
        Map<String, Object> detail = stageDetail(stageInstance, stage, cancelled);
        actions.record(instance, stageInstance.getStageInstanceId(), null, WorkflowActionType.stage_approved, null,
                null, detail);
        audit(AuditActionCatalog.WORKFLOW_STAGE_COMPLETED, instance)
                .newValue(instanceSummary(instance, withOutcome(detail, "approved")))
                .log();

        stageInstanceRepository.findFirstByInstanceIdAndStatusOrderByStageOrderAsc(instance.getInstanceId(),
                        StageInstanceStatus.not_started)
                .ifPresentOrElse(next -> activateStage(instance, next, actorUserId),
                        () -> complete(instance, InstanceStatus.approved, null, actorUserId, now));
    }

    private void rejectStage(WorkflowInstance instance, WorkflowStageInstance stageInstance, WorkflowStage stage,
                             List<WorkflowTask> remainingOpen, UUID actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> cancelled = cancelTasks(instance, remainingOpen, CAUSE_WORKFLOW_REJECTED, actorUserId, now);
        closeStage(stageInstance, StageInstanceStatus.rejected, actorUserId, now);
        skipRemainingStages(instance, actorUserId, now);

        // Under wait_for_all the concluding response may be an approval, so the
        // reason comes from the stage's rejected tasks, not from the last actor.
        List<WorkflowTask> rejections = taskRepository
                .findByStageInstanceIdAndDeletedFalseOrderByCreatedAtAsc(stageInstance.getStageInstanceId()).stream()
                .filter(t -> t.getStatus() == TaskStatus.rejected)
                .sorted((x, y) -> x.getActedAt().compareTo(y.getActedAt()))
                .toList();
        String outcomeReason;
        if (rejections.size() == 1) {
            outcomeReason = rejections.get(0).getDecisionReason();
        } else {
            Map<UUID, String> names = new HashMap<>();
            userRepository.findAllById(rejections.stream().map(WorkflowTask::getActedBy).toList())
                    .forEach(u -> names.put(u.getUserId(), u.getDisplayName()));
            outcomeReason = String.join("; ", rejections.stream()
                    .map(t -> names.getOrDefault(t.getActedBy(), "Approver") + ": " + t.getDecisionReason())
                    .toList());
        }

        Map<String, Object> detail = stageDetail(stageInstance, stage, cancelled);
        actions.record(instance, stageInstance.getStageInstanceId(), null, WorkflowActionType.stage_rejected, null,
                null, detail);
        audit(AuditActionCatalog.WORKFLOW_STAGE_COMPLETED, instance)
                .newValue(instanceSummary(instance, withOutcome(detail, "rejected")))
                .log();

        List<Map<String, Object>> rejectionDetail = new ArrayList<>();
        for (WorkflowTask t : rejections) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("taskId", t.getTaskId());
            r.put("actedBy", t.getActedBy());
            r.put("reason", t.getDecisionReason());
            rejectionDetail.add(r);
        }
        complete(instance, InstanceStatus.rejected, outcomeReason, actorUserId, now,
                Map.of("rejections", rejectionDetail));
    }

    private void complete(WorkflowInstance instance, InstanceStatus status, String reason, UUID actorUserId,
                          OffsetDateTime now) {
        complete(instance, status, reason, actorUserId, now, Map.of());
    }

    private void complete(WorkflowInstance instance, InstanceStatus status, String reason, UUID actorUserId,
                          OffsetDateTime now, Map<String, Object> detail) {
        instance.setStatus(status);
        instance.setCompletedAt(now);
        instance.setOutcomeReason(truncate(reason));
        instance.setCurrentStageInstanceId(null);
        instance.setUpdatedBy(actorUserId);
        instance.setUpdatedAt(now);
        instanceRepository.saveAndFlush(instance);

        boolean approved = status == InstanceStatus.approved;
        actions.record(instance, null, null,
                approved ? WorkflowActionType.workflow_approved : WorkflowActionType.workflow_rejected,
                null, reason, detail);
        audit(approved ? AuditActionCatalog.WORKFLOW_APPROVED : AuditActionCatalog.WORKFLOW_REJECTED, instance)
                .reason(reason)
                .newValue(instanceSummary(instance, detail))
                .log();
        eventPublisher.publishEvent(new WorkflowOutcomeEvent(instance.getTenantId(), instance.getInstanceId(),
                actorUserId));
    }

    /**
     * Cancels open tasks the stage or workflow no longer needs. Their open
     * information requests are cancelled first with a logged reason
     * (decision 16), and a paused SLA clock is folded into
     * sla_paused_seconds. The tasks are already locked, so the requests are
     * locked after them (decision 22).
     */
    private List<UUID> cancelTasks(WorkflowInstance instance, List<WorkflowTask> tasks, String cause,
                                   UUID actorUserId, OffsetDateTime now) {
        cancelOpenRequests(instance, tasks.stream().filter(t -> t.getStatus() == TaskStatus.awaiting_info).toList(),
                cause, actorUserId, now);
        List<UUID> ids = new ArrayList<>();
        for (WorkflowTask t : tasks) {
            if (!TaskStatus.OPEN.contains(t.getStatus())) {
                continue;
            }
            foldPause(t, now);
            t.setStatus(TaskStatus.cancelled);
            t.setUpdatedBy(actorUserId);
            t.setUpdatedAt(now);
            ids.add(t.getTaskId());
        }
        taskRepository.saveAllAndFlush(tasks);
        return ids;
    }

    private void closeStage(WorkflowStageInstance stageInstance, StageInstanceStatus status, UUID actorUserId,
                            OffsetDateTime now) {
        stageInstance.setStatus(status);
        stageInstance.setCompletedAt(now);
        stageInstance.setUpdatedBy(actorUserId);
        stageInstance.setUpdatedAt(now);
        // Flushed before the next stage activates - uq_workflow_stage_instances_one_active.
        stageInstanceRepository.saveAndFlush(stageInstance);
    }

    private void skipRemainingStages(WorkflowInstance instance, UUID actorUserId, OffsetDateTime now) {
        for (WorkflowStageInstance si : stageInstanceRepository.findByInstanceIdOrderByStageOrderAsc(instance.getInstanceId())) {
            if (si.getStatus() == StageInstanceStatus.not_started || si.getStatus() == StageInstanceStatus.active) {
                si.setStatus(StageInstanceStatus.skipped);
                si.setCompletedAt(now);
                si.setUpdatedBy(actorUserId);
                si.setUpdatedAt(now);
                stageInstanceRepository.save(si);
            }
        }
        stageInstanceRepository.flush();
    }

    private static Map<String, Object> stageDetail(WorkflowStageInstance si, WorkflowStage stage, List<UUID> cancelled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stageInstanceId", si.getStageInstanceId());
        m.put("stageOrder", si.getStageOrder());
        m.put("stageName", stage.getName());
        m.put("completionRule", stage.getCompletionRule().name());
        m.put("rejectionRule", stage.getRejectionRule().name());
        m.put("entryCount", si.getEntryCount());
        m.put("requiredCount", si.getRequiredCount());
        m.put("approvedCount", si.getApprovedCount());
        m.put("rejectedCount", si.getRejectedCount());
        m.put("cancelledTaskIds", cancelled.stream().map(UUID::toString).toList());
        return m;
    }

    private static Map<String, Object> withOutcome(Map<String, Object> detail, String outcome) {
        Map<String, Object> m = new LinkedHashMap<>(detail);
        m.put("stageOutcome", outcome);
        return m;
    }

    static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }

    /** Display name of a user in the tenant, or null. */
    public String displayName(UUID userId) {
        return userId == null ? null : userRepository.findById(userId).map(User::getDisplayName).orElse(null);
    }
}
