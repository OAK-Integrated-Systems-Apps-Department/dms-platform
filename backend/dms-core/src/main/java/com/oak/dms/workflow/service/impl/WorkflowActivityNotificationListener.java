package com.oak.dms.workflow.service.impl;

import com.oak.dms.user.User;
import com.oak.dms.workflow.entity.WorkflowInfoRequest;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.InfoRequestStatus;
import com.oak.dms.workflow.event.WorkflowInfoRequestEvent;
import com.oak.dms.workflow.event.WorkflowTaskDelegatedEvent;
import com.oak.dms.workflow.event.WorkflowTaskEscalatedEvent;
import com.oak.dms.workflow.event.WorkflowTaskReminderEvent;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer.Doc;
import com.oak.dms.workflow.helper.WorkflowRecipients;
import com.oak.dms.workflow.repository.WorkflowInfoRequestRepository;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowSupportingDocumentRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2 workflow notifications (S9): delegation and reassignment, reminders
 * (due-date and inactivity), escalation and information requests. Same rules
 * as {@link WorkflowNotificationListener} - BEFORE_COMMIT, written through the
 * shared notification service in the raising transaction, one call per event's
 * recipients.
 */
@Component
@RequiredArgsConstructor
public class WorkflowActivityNotificationListener {

    /** Readable wording of EscalationTargetResolver's skipped reasons. */
    private static final Map<String, String> WHY_NOT_ESCALATED = Map.of(
            "no_target", "no escalation target is set (for a department head: the department has no head)",
            "target_inactive", "the escalation target is no longer an active user",
            "target_is_initiator", "the escalation target is the person who submitted the document",
            "target_is_holder", "the escalation target already holds this task",
            "target_already_decided", "the escalation target already decided in this stage",
            "target_lacks_permission", "the escalation target has no permission to approve or reject");

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowInfoRequestRepository infoRequestRepository;
    private final WorkflowSupportingDocumentRepository supportingRepository;
    private final TaskRouting taskRouting;
    private final WorkflowRecipients recipients;
    private final WorkflowNotificationMailer mailer;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTaskMoved(WorkflowTaskDelegatedEvent event) {
        notifyTaskMoved(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onReminder(WorkflowTaskReminderEvent event) {
        notifyReminder(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onEscalated(WorkflowTaskEscalatedEvent event) {
        notifyEscalated(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onInfoRequest(WorkflowInfoRequestEvent event) {
        notifyInfoRequest(event);
    }

    private record Context(WorkflowInstance instance, WorkflowTask task, String stageName, Doc doc) {
    }

    private Context context(UUID taskId) {
        WorkflowTask task = taskRepository.findById(taskId).orElse(null);
        WorkflowStageInstance si = task == null ? null : stageInstanceRepository.findById(task.getStageInstanceId())
                .orElse(null);
        WorkflowStage stage = si == null ? null : stageRepository.findById(si.getStageId()).orElse(null);
        WorkflowInstance instance = si == null ? null : instanceRepository.findById(si.getInstanceId()).orElse(null);
        if (stage == null || instance == null) {
            return null;
        }
        return new Context(instance, task, stage.getName(),
                new Doc(instance.getDocumentId(), recipients.documentTitle(instance.getDocumentId())));
    }

    private void notifyTaskMoved(WorkflowTaskDelegatedEvent event) {
        Context c = context(event.taskId());
        if (c == null) {
            return;
        }
        UUID tenantId = event.tenantId();
        String fromName = name(event.previousHolderId(), tenantId);
        String toName = name(event.newHolderId(), tenantId);
        User newHolder = recipients.activeUser(event.newHolderId(), tenantId);
        if (newHolder != null) {
            mailer.sendTaskMoved(List.of(newHolder), true, event.kind(), c.doc(), c.stageName(), fromName, toName,
                    event.note());
        }
        User previous = recipients.activeUser(event.previousHolderId(), tenantId);
        if (previous != null && !previous.getUserId().equals(event.newHolderId())) {
            mailer.sendTaskMoved(List.of(previous), false, event.kind(), c.doc(), c.stageName(), fromName, toName,
                    event.note());
        }
    }

    private void notifyReminder(WorkflowTaskReminderEvent event) {
        Context c = context(event.taskId());
        if (c == null) {
            return; // an inactivity reminder has no due date, so dueAt may be null here
        }
        Set<UUID> holders = taskRouting.currentHolders(event.tenantId(), c.task(), c.instance().getInitiatedBy());
        mailer.sendReminder(recipients.activeUsers(holders, event.tenantId()).values(), c.doc(), c.stageName(),
                c.task().getDueAt());
    }

    private void notifyEscalated(WorkflowTaskEscalatedEvent event) {
        Context c = context(event.taskId());
        if (c == null) {
            return;
        }
        UUID tenantId = event.tenantId();
        if (event.targetUserId() == null) {
            String why = WHY_NOT_ESCALATED.getOrDefault(event.skippedReason(), event.skippedReason());
            mailer.sendEscalationFailed(
                    recipients.activeUsers(recipients.reassignersAndAdmins(tenantId), tenantId).values(), c.doc(),
                    c.stageName(), why, c.instance().getInstanceId());
            return;
        }
        User target = recipients.activeUser(event.targetUserId(), tenantId);
        if (target != null) {
            mailer.sendEscalated(List.of(target), true, c.doc(), c.stageName(), null);
        }
        String targetName = name(event.targetUserId(), tenantId);
        Set<UUID> holders = new LinkedHashSet<>(taskRouting.currentHolders(tenantId, c.task(),
                c.instance().getInitiatedBy()));
        holders.remove(event.targetUserId());
        mailer.sendEscalated(recipients.activeUsers(holders, tenantId).values(), false, c.doc(), c.stageName(),
                targetName);
    }

    private void notifyInfoRequest(WorkflowInfoRequestEvent event) {
        UUID tenantId = event.tenantId();
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        if (instance == null) {
            return;
        }
        Doc doc = new Doc(instance.getDocumentId(), recipients.documentTitle(instance.getDocumentId()));
        List<WorkflowInfoRequest> requests = infoRequestRepository.findAllById(event.infoRequestIds());
        for (WorkflowInfoRequest r : requests) {
            switch (event.kind()) {
                case "requested" -> mailRequested(r, tenantId, doc);
                case "reassigned" -> {
                    mailRequested(r, tenantId, doc);
                    mailClosed(event.previousRequestedFrom(), tenantId, doc,
                            "it was redirected to " + name(r.getRequestedFrom(), tenantId), instance);
                }
                case "answered", "declined" -> {
                    User requester = recipients.activeUser(r.getRequestedBy(), tenantId);
                    if (requester != null) {
                        boolean declined = r.getStatus() == InfoRequestStatus.declined;
                        int documents = supportingRepository.findByInfoRequestIdInAndTenantIdAndDeletedFalse(
                                List.of(r.getInfoRequestId()), tenantId).size();
                        String responder = name(r.getRespondedBy(), tenantId);
                        mailer.sendInfoAnswered(List.of(requester), doc, responder, declined,
                                declined ? r.getDeclineReason() : r.getResponseMessage(), documents,
                                instance.getInstanceId());
                    }
                }
                case "withdrawn" -> mailClosed(r.getRequestedFrom(), tenantId, doc, r.getCancelReason(), instance);
                case "cancelled" -> {
                    // Closed by the engine: both sides learn why (decision 16).
                    mailClosed(r.getRequestedFrom(), tenantId, doc, r.getCancelReason(), instance);
                    mailClosed(r.getRequestedBy(), tenantId, doc, r.getCancelReason(), instance);
                }
                default -> {
                }
            }
        }
    }

    private void mailRequested(WorkflowInfoRequest r, UUID tenantId, Doc doc) {
        User to = recipients.activeUser(r.getRequestedFrom(), tenantId);
        if (to != null) {
            String requester = name(r.getRequestedBy(), tenantId);
            mailer.sendInfoRequested(List.of(to), doc, requester, r.getMessage(), r.getDueAt());
        }
    }

    private void mailClosed(UUID userId, UUID tenantId, Doc doc, String reason, WorkflowInstance instance) {
        User to = recipients.activeUser(userId, tenantId);
        if (to != null) {
            mailer.sendInfoClosed(List.of(to), doc, reason, instance.getInstanceId());
        }
    }

    private String name(UUID userId, UUID tenantId) {
        User u = userId == null ? null : recipients.activeUsers(List.of(userId), tenantId).get(userId);
        return u != null && u.getDisplayName() != null ? u.getDisplayName() : "someone";
    }
}
