package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.dto.InfoRequestView;
import com.oak.dms.workflow.dto.RespondInfoRequest;
import com.oak.dms.workflow.dto.SupportingDocumentInput;
import com.oak.dms.workflow.dto.SupportingDocumentView;
import com.oak.dms.workflow.entity.WorkflowInfoRequest;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowSupportingDocument;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.InfoRequestStatus;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.StageInstanceStatus;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.event.WorkflowInfoRequestEvent;
import com.oak.dms.workflow.exception.WorkflowAccessException;
import com.oak.dms.workflow.exception.WorkflowRequestException;
import com.oak.dms.workflow.exception.WorkflowStateException;
import com.oak.dms.workflow.exception.WorkflowValidationException;
import com.oak.dms.workflow.helper.TaskLocker;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.helper.WorkflowEvidenceMapper;
import com.oak.dms.workflow.helper.WorkflowLocks;
import com.oak.dms.workflow.repository.WorkflowInfoRequestRepository;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.WorkflowInfoRequestService;
import com.oak.dms.workflow.service.WorkflowQueryService;
import com.oak.dms.workflow.service.WorkflowSupportingDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2 plan §3, §7, §8. Locks: instance, stage instance, task
 * ({@link TaskLocker}), then the task's open requests in info_request_id
 * order - so "is this the last open request?" is exact under concurrency.
 */
@Service
@RequiredArgsConstructor
public class WorkflowInfoRequestServiceImpl implements WorkflowInfoRequestService {

    /** Open requests per task. */
    static final int MAX_OPEN_PER_TASK = 10;
    private static final String WITHDRAWN = "Withdrawn by the requester";

    private final TaskLocker taskLocker;
    private final TaskRouting taskRouting;
    private final WorkflowInfoRequestRepository requestRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowSupportingDocumentService supportingDocumentService;
    private final WorkflowQueryService queryService;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final WorkflowActionRecorder actions;
    private final WorkflowEngine engine;
    private final WorkflowEvidenceMapper evidenceMapper;
    private final WorkflowLocks locks;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public InfoRequestView raise(UUID taskId, UUID requestedFromUserId, String message, OffsetDateTime dueAt) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        TaskLocker.Locked locked = taskLocker.lock(taskId, tenantId);
        WorkflowInstance instance = locked.instance();
        WorkflowStageInstance stage = locked.stage();
        WorkflowTask task = locked.task();

        requireRunning(instance, stage);
        if (userId.equals(instance.getInitiatedBy())) {
            throw new WorkflowAccessException("WORKFLOW_SELF_APPROVAL_FORBIDDEN",
                    "You cannot act on a workflow you submitted");
        }
        if (taskRepository.existsByStageInstanceIdAndActedByAndStatusIn(stage.getStageInstanceId(), userId,
                TaskStatus.DECIDED)) {
            throw new WorkflowStateException("WORKFLOW_ALREADY_ACTED_IN_STAGE", "You have already decided this stage");
        }
        if (!TaskStatus.OPEN.contains(task.getStatus())) {
            throw new WorkflowStateException(TaskStatus.DECIDED.contains(task.getStatus())
                    ? "WORKFLOW_ENTRY_SATISFIED" : "WORKFLOW_TASK_CLOSED", "This task is no longer open");
        }
        WorkflowStage def = stageRepository.findById(stage.getStageId()).orElseThrow();
        if (!def.isAllowInfoRequests()) {
            throw new WorkflowValidationException("WORKFLOW_INFO_REQUESTS_DISABLED",
                    "Information requests are switched off for the stage \"" + def.getName() + "\"");
        }
        if (!taskRouting.canAct(tenantId, task, userId)) {
            if (task.isPool() && task.getClaimedBy() != null && task.getDelegatedTo() == null) {
                throw new WorkflowStateException("WORKFLOW_TASK_ALREADY_CLAIMED", "Another approver has claimed this task");
            }
            throw new WorkflowAccessException("WORKFLOW_NOT_ASSIGNEE", "You are not an approver of this task");
        }
        User target = requireTarget(tenantId, requestedFromUserId, userId);
        if (dueAt != null && !dueAt.isAfter(OffsetDateTime.now())) {
            throw new WorkflowValidationException("WORKFLOW_INVALID_INFO_REQUEST", "The due date must be in the future");
        }
        List<WorkflowInfoRequest> open = requestRepository.lockOpenByTaskIds(tenantId, List.of(taskId));
        if (open.size() >= MAX_OPEN_PER_TASK) {
            throw new WorkflowValidationException("WORKFLOW_TOO_MANY_INFO_REQUESTS",
                    "A task can have at most " + MAX_OPEN_PER_TASK + " open information requests");
        }

        OffsetDateTime now = OffsetDateTime.now();
        // Decision 12: raising a request on an unclaimed pool task claims it for the requester.
        if (task.isPool() && task.getClaimedBy() == null) {
            task.setClaimedBy(userId);
            task.setClaimedAt(now);
        }
        if (task.getStatus() != TaskStatus.awaiting_info) {
            task.setStatus(TaskStatus.awaiting_info);
            task.setSlaPausedAt(now);   // decision 14: the clock stops on the first open request
        }
        task.setUpdatedBy(userId);
        task.setUpdatedAt(now);
        taskRepository.saveAndFlush(task);

        WorkflowInfoRequest request = requestRepository.saveAndFlush(WorkflowInfoRequest.builder()
                .tenantId(tenantId)
                .instanceId(instance.getInstanceId())
                .stageInstanceId(stage.getStageInstanceId())
                .taskId(taskId)
                .requestedBy(userId)
                .requestedFrom(target.getUserId())
                .message(message.trim())
                .dueAt(dueAt)
                .requestedAt(now)
                .createdBy(userId)
                .createdAt(now)
                .build());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stageOrder", stage.getStageOrder());
        detail.put("requestedFrom", target.getUserId().toString());
        detail.put("requestedFromName", target.getDisplayName());
        actions.record(instance, stage.getStageInstanceId(), taskId, request.getInfoRequestId(),
                WorkflowActionType.info_requested, userId, request.getMessage(), detail);
        engine.infoRequestAudit(AuditActionCatalog.WORKFLOW_INFO_REQUESTED, instance, request, detail).log();
        publish(instance, request, "requested", userId, null);
        return view(request, stage, instance, List.of());
    }

    @Override
    @Transactional
    public InfoRequestView respond(UUID infoRequestId, RespondInfoRequest response) {
        UUID userId = TenantContext.getUserId();
        String message = response.message() == null || response.message().isBlank() ? null : response.message().trim();
        List<SupportingDocumentInput> documents = response.supportingDocuments() == null ? List.of()
                : response.supportingDocuments();
        if (message == null && documents.isEmpty()) {
            throw new WorkflowValidationException("WORKFLOW_INFO_RESPONSE_EMPTY",
                    "Add a note or at least one supporting document");
        }
        Closing c = lockForClosing(infoRequestId);
        requireRequestedPerson(c.request(), userId);

        List<WorkflowSupportingDocument> attached = documents.isEmpty() ? List.of()
                : supportingDocumentService.attach(c.locked().instance(), infoRequestId, documents, userId);
        OffsetDateTime now = OffsetDateTime.now();
        WorkflowInfoRequest request = c.request();
        request.setStatus(InfoRequestStatus.answered);
        request.setResponseMessage(message);
        request.setRespondedAt(now);
        request.setRespondedBy(userId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestedBy", request.getRequestedBy().toString());
        detail.put("supportingDocumentIds", attached.stream()
                .map(d -> d.getSupportingDocumentId().toString()).toList());
        close(c, request, WorkflowActionType.info_provided, AuditActionCatalog.WORKFLOW_INFO_PROVIDED, message, detail,
                userId, now);
        publish(c.locked().instance(), request, "answered", userId, null);
        return view(request, c.locked().stage(), c.locked().instance(),
                supportingDocumentService.views(request.getTenantId(), attached));
    }

    @Override
    @Transactional
    public InfoRequestView decline(UUID infoRequestId, String reason) {
        UUID userId = TenantContext.getUserId();
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (cleanReason == null) {
            throw new WorkflowRequestException("WORKFLOW_REASON_REQUIRED", "A reason is required to decline a request");
        }
        Closing c = lockForClosing(infoRequestId);
        requireRequestedPerson(c.request(), userId);
        OffsetDateTime now = OffsetDateTime.now();
        WorkflowInfoRequest request = c.request();
        request.setStatus(InfoRequestStatus.declined);
        request.setDeclineReason(cleanReason);
        request.setRespondedAt(now);
        request.setRespondedBy(userId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestedBy", request.getRequestedBy().toString());
        close(c, request, WorkflowActionType.info_declined, AuditActionCatalog.WORKFLOW_INFO_DECLINED, cleanReason,
                detail, userId, now);
        publish(c.locked().instance(), request, "declined", userId, null);
        return view(request, c.locked().stage(), c.locked().instance(), List.of());
    }

    @Override
    @Transactional
    public InfoRequestView withdraw(UUID infoRequestId, String reason) {
        UUID userId = TenantContext.getUserId();
        Closing c = lockForClosing(infoRequestId);
        WorkflowInfoRequest request = c.request();
        if (!userId.equals(request.getRequestedBy())) {
            throw new WorkflowAccessException("WORKFLOW_NOT_REQUESTER", "Only the person who asked can withdraw a request");
        }
        String cleanReason = reason == null || reason.isBlank() ? WITHDRAWN : reason.trim();
        OffsetDateTime now = OffsetDateTime.now();
        request.setStatus(InfoRequestStatus.cancelled);
        request.setCancelReason(cleanReason);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("cause", "withdrawn_by_requester");
        detail.put("requestedFrom", request.getRequestedFrom().toString());
        close(c, request, WorkflowActionType.info_cancelled, AuditActionCatalog.WORKFLOW_INFO_CANCELLED, cleanReason,
                detail, userId, now);
        publish(c.locked().instance(), request, "withdrawn", userId, null);
        return view(request, c.locked().stage(), c.locked().instance(), List.of());
    }

    @Override
    @Transactional
    public InfoRequestView reassign(UUID infoRequestId, UUID requestedFromUserId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        UUID instanceId = parentIds(infoRequestId, tenantId)[0];
        locks.applyLockTimeout();
        // Instance, then the request: a prefix of the global order (the stage and task don't change).
        WorkflowInstance instance = instanceRepository.lockByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        WorkflowInfoRequest request = requestRepository.lockByIdAndTenantId(infoRequestId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInfoRequest", infoRequestId));
        if (!userId.equals(instance.getInitiatedBy()) && !authorizationService.isTenantAdmin()) {
            throw new WorkflowAccessException("WORKFLOW_INFO_REASSIGN_FORBIDDEN",
                    "Only the submitter or an administrator can redirect a request");
        }
        if (request.getStatus() != InfoRequestStatus.open || instance.getStatus() != InstanceStatus.in_progress) {
            throw new WorkflowStateException("WORKFLOW_INFO_REQUEST_CLOSED", "This request is no longer open");
        }
        UUID previous = request.getRequestedFrom();
        if (previous.equals(requestedFromUserId)) {
            throw new WorkflowValidationException("WORKFLOW_INVALID_INFO_TARGET", "This request is already with that person");
        }
        User target = requireTarget(tenantId, requestedFromUserId, request.getRequestedBy());
        OffsetDateTime now = OffsetDateTime.now();
        request.setRequestedFrom(target.getUserId());
        request.setUpdatedBy(userId);
        request.setUpdatedAt(now);
        requestRepository.saveAndFlush(request);
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", previous.toString());
        detail.put("fromName", engine.displayName(previous));
        detail.put("to", target.getUserId().toString());
        detail.put("toName", target.getDisplayName());
        actions.record(instance, request.getStageInstanceId(), request.getTaskId(), infoRequestId,
                WorkflowActionType.info_reassigned, userId, cleanReason, detail);
        engine.infoRequestAudit(AuditActionCatalog.WORKFLOW_INFO_REQUEST_REASSIGNED, instance, request, detail)
                .reason(cleanReason).log();
        publish(instance, request, "reassigned", userId, previous);
        WorkflowStageInstance stage = stageInstanceRepository.findByStageInstanceIdAndTenantId(
                request.getStageInstanceId(), tenantId).orElse(null);
        return view(request, stage, instance, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InfoRequestView> listForInstance(UUID instanceId) {
        UUID tenantId = TenantContext.getTenantId();
        queryService.requireVisible(instanceId);
        return views(tenantId, requestRepository.findByInstanceIdAndTenantIdAndDeletedFalseOrderByRequestedAtAsc(
                instanceId, tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InfoRequestView> addressedToMe(InfoRequestStatus status, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        Page<WorkflowInfoRequest> page = requestRepository.findAddressedTo(tenantId, TenantContext.getUserId(), status,
                pageable);
        List<InfoRequestView> views = views(tenantId, page.getContent());
        return page.map(r -> views.stream().filter(v -> v.infoRequestId().equals(r.getInfoRequestId()))
                .findFirst().orElseThrow());
    }

    // ---------------------------------------------------------------- closing

    private record Closing(TaskLocker.Locked locked, WorkflowInfoRequest request, List<WorkflowInfoRequest> open) {
    }

    /**
     * Locks the task's chain and every open request of the task (§8), and
     * returns this request among them - 409 when it has already closed.
     */
    private Closing lockForClosing(UUID infoRequestId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID taskId = parentIds(infoRequestId, tenantId)[2];
        TaskLocker.Locked locked = taskLocker.lock(taskId, tenantId);
        List<WorkflowInfoRequest> open = requestRepository.lockOpenByTaskIds(tenantId, List.of(taskId));
        WorkflowInfoRequest request = open.stream().filter(r -> r.getInfoRequestId().equals(infoRequestId))
                .findFirst()
                .orElseThrow(() -> new WorkflowStateException("WORKFLOW_INFO_REQUEST_CLOSED",
                        "This request is no longer open"));
        return new Closing(locked, request, open);
    }

    /**
     * Records the closing and, when it was the last open request, resumes
     * the task: the paused time moves due_at forward (decision 14) and the
     * task can be decided again (decision 13).
     */
    private void close(Closing c, WorkflowInfoRequest request, WorkflowActionType type, String auditAction,
                       String reason, Map<String, Object> detail, UUID userId, OffsetDateTime now) {
        request.setUpdatedBy(userId);
        request.setUpdatedAt(now);
        requestRepository.saveAndFlush(request);
        WorkflowInstance instance = c.locked().instance();
        WorkflowTask task = c.locked().task();
        boolean last = c.open().stream().noneMatch(r -> !r.getInfoRequestId().equals(request.getInfoRequestId()));
        detail.put("taskResumed", last);
        actions.record(instance, request.getStageInstanceId(), request.getTaskId(), request.getInfoRequestId(), type,
                userId, reason, detail);
        engine.infoRequestAudit(auditAction, instance, request, detail).reason(reason).log();
        if (last && task.getStatus() == TaskStatus.awaiting_info) {
            engine.resume(task, now);
            task.setUpdatedBy(userId);
            taskRepository.saveAndFlush(task);
        }
    }

    // ---------------------------------------------------------------- guards and helpers

    private static void requireRunning(WorkflowInstance instance, WorkflowStageInstance stage) {
        if (stage.getStatus() != StageInstanceStatus.active) {
            throw new WorkflowStateException("WORKFLOW_STAGE_NOT_ACTIVE",
                    "This stage is not open - it has not been reached yet, or has already closed");
        }
        if (instance.getStatus() != InstanceStatus.in_progress) {
            throw new WorkflowStateException("WORKFLOW_NOT_IN_PROGRESS", "This workflow has already finished");
        }
    }

    private static void requireRequestedPerson(WorkflowInfoRequest request, UUID userId) {
        if (!userId.equals(request.getRequestedFrom())) {
            throw new WorkflowAccessException("WORKFLOW_NOT_REQUESTED_PERSON",
                    "Only the person this request is addressed to can answer it");
        }
    }

    /** Decision 15: any active user of the tenant other than the requester. */
    private User requireTarget(UUID tenantId, UUID targetId, UUID requesterId) {
        User target = userRepository.findByUserIdAndTenantIdAndDeletedFalse(targetId, tenantId)
                .filter(u -> u.getStatus() == User.UserStatus.active)
                .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_INVALID_INFO_TARGET",
                        "The selected person does not exist or is not active"));
        if (target.getUserId().equals(requesterId)) {
            throw new WorkflowValidationException("WORKFLOW_INVALID_INFO_TARGET", "You can't ask yourself for information");
        }
        return target;
    }

    /** [instanceId, stageInstanceId, taskId] as scalars - nothing is loaded before its lock. */
    private UUID[] parentIds(UUID infoRequestId, UUID tenantId) {
        List<Object[]> rows = requestRepository.findParentIds(infoRequestId, tenantId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("WorkflowInfoRequest", infoRequestId);
        }
        Object[] row = rows.get(0);
        return new UUID[]{(UUID) row[0], (UUID) row[1], (UUID) row[2]};
    }

    private void publish(WorkflowInstance instance, WorkflowInfoRequest request, String kind, UUID actorUserId,
                         UUID previousRequestedFrom) {
        eventPublisher.publishEvent(new WorkflowInfoRequestEvent(instance.getTenantId(), instance.getInstanceId(),
                List.of(request.getInfoRequestId()), kind, actorUserId, previousRequestedFrom));
    }

    private InfoRequestView view(WorkflowInfoRequest r, WorkflowStageInstance stage, WorkflowInstance instance,
                                 List<SupportingDocumentView> documents) {
        return evidenceMapper.requestViews(r.getTenantId(), List.of(r)).get(0);
    }

    private List<InfoRequestView> views(UUID tenantId, List<WorkflowInfoRequest> requests) {
        return evidenceMapper.requestViews(tenantId, requests);
    }
}
