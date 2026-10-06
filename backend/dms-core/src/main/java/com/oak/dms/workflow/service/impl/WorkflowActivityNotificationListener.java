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
import com.oak.dms.workflow.helper.NotificationDispatcher;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer;
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
 * Phase 2 workflow mail (S9): delegation and reassignment, reminders,
 * escalation and information requests. Same rules as
 * {@link WorkflowNotificationListener} - after commit, off the request
 * thread, one try per recipient, best effort (decision 24).
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
    private final NotificationDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskMoved(WorkflowTaskDelegatedEvent event) {
        dispatcher.dispatch(event.kind(), event.taskId(), () -> notifyTaskMoved(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReminder(WorkflowTaskReminderEvent event) {
        dispatcher.dispatch("reminder", event.taskId(), () -> notifyReminder(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEscalated(WorkflowTaskEscalatedEvent event) {
        dispatcher.dispatch("escalation", event.taskId(), () -> notifyEscalated(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInfoRequest(WorkflowInfoRequestEvent event) {
        dispatcher.dispatch("info-" + event.kind(), event.instanceId(), () -> notifyInfoRequest(event));
    }

    private record Context(WorkflowInstance instance, WorkflowTask task, String stageName, String title) {
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
        return new Context(instance, task, stage.getName(), recipients.documentTitle(instance.getDocumentId()));
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
            NotificationDispatcher.send(() -> mailer.sendTaskMoved(newHolder, true, event.kind(), c.title(), c.stageName(),
                    fromName, toName, event.note()), event.kind(), newHolder, event.taskId());
        }
        User previous = recipients.activeUser(event.previousHolderId(), tenantId);
        if (previous != null && !previous.getUserId().equals(event.newHolderId())) {
            NotificationDispatcher.send(() -> mailer.sendTaskMoved(previous, false, event.kind(), c.title(), c.stageName(),
                    fromName, toName, event.note()), event.kind(), previous, event.taskId());
        }
    }

    private void notifyReminder(WorkflowTaskReminderEvent event) {
        Context c = context(event.taskId());
        if (c == null || c.task().getDueAt() == null) {
            return;
        }
        Set<UUID> holders = taskRouting.currentHolders(event.tenantId(), c.task(), c.instance().getInitiatedBy());
        for (User user : recipients.activeUsers(holders, event.tenantId()).values()) {
            NotificationDispatcher.send(() -> mailer.sendReminder(user, c.title(), c.stageName(), c.task().getDueAt()),
                    "reminder", user, event.taskId());
        }
    }

    private void notifyEscalated(WorkflowTaskEscalatedEvent event) {
        Context c = context(event.taskId());
        if (c == null) {
            return;
        }
        UUID tenantId = event.tenantId();
        if (event.targetUserId() == null) {
            String why = WHY_NOT_ESCALATED.getOrDefault(event.skippedReason(), event.skippedReason());
            for (User user : recipients.activeUsers(recipients.reassignersAndAdmins(tenantId), tenantId).values()) {
                NotificationDispatcher.send(() -> mailer.sendEscalationFailed(user, c.title(), c.stageName(), why,
                        c.instance().getInstanceId()), "escalation-failed", user, event.taskId());
            }
            return;
        }
        User target = recipients.activeUser(event.targetUserId(), tenantId);
        if (target != null) {
            NotificationDispatcher.send(() -> mailer.sendEscalated(target, true, c.title(), c.stageName(), null),
                    "escalation", target, event.taskId());
        }
        String targetName = name(event.targetUserId(), tenantId);
        Set<UUID> holders = new LinkedHashSet<>(taskRouting.currentHolders(tenantId, c.task(),
                c.instance().getInitiatedBy()));
        holders.remove(event.targetUserId());
        for (User user : recipients.activeUsers(holders, tenantId).values()) {
            NotificationDispatcher.send(() -> mailer.sendEscalated(user, false, c.title(), c.stageName(), targetName),
                    "escalation-copy", user, event.taskId());
        }
    }

    private void notifyInfoRequest(WorkflowInfoRequestEvent event) {
        UUID tenantId = event.tenantId();
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        if (instance == null) {
            return;
        }
        String title = recipients.documentTitle(instance.getDocumentId());
        List<WorkflowInfoRequest> requests = infoRequestRepository.findAllById(event.infoRequestIds());
        for (WorkflowInfoRequest r : requests) {
            switch (event.kind()) {
                case "requested" -> mailRequested(r, tenantId, title);
                case "reassigned" -> {
                    mailRequested(r, tenantId, title);
                    mailClosed(event.previousRequestedFrom(), tenantId, title,
                            "it was redirected to " + name(r.getRequestedFrom(), tenantId), instance);
                }
                case "answered", "declined" -> {
                    User requester = recipients.activeUser(r.getRequestedBy(), tenantId);
                    if (requester != null) {
                        boolean declined = r.getStatus() == InfoRequestStatus.declined;
                        int documents = supportingRepository.findByInfoRequestIdInAndTenantIdAndDeletedFalse(
                                List.of(r.getInfoRequestId()), tenantId).size();
                        String responder = name(r.getRespondedBy(), tenantId);
                        NotificationDispatcher.send(() -> mailer.sendInfoAnswered(requester, title, responder, declined,
                                declined ? r.getDeclineReason() : r.getResponseMessage(), documents,
                                instance.getInstanceId()), "info-" + event.kind(), requester, r.getInfoRequestId());
                    }
                }
                case "withdrawn" -> mailClosed(r.getRequestedFrom(), tenantId, title, r.getCancelReason(), instance);
                case "cancelled" -> {
                    // Closed by the engine: both sides learn why (decision 16).
                    mailClosed(r.getRequestedFrom(), tenantId, title, r.getCancelReason(), instance);
                    mailClosed(r.getRequestedBy(), tenantId, title, r.getCancelReason(), instance);
                }
                default -> {
                }
            }
        }
    }

    private void mailRequested(WorkflowInfoRequest r, UUID tenantId, String title) {
        User to = recipients.activeUser(r.getRequestedFrom(), tenantId);
        if (to != null) {
            String requester = name(r.getRequestedBy(), tenantId);
            NotificationDispatcher.send(() -> mailer.sendInfoRequested(to, title, requester, r.getMessage(), r.getDueAt()),
                    "info-requested", to, r.getInfoRequestId());
        }
    }

    private void mailClosed(UUID userId, UUID tenantId, String title, String reason, WorkflowInstance instance) {
        User to = recipients.activeUser(userId, tenantId);
        if (to != null) {
            NotificationDispatcher.send(() -> mailer.sendInfoClosed(to, title, reason, instance.getInstanceId()),
                    "info-closed", to, instance.getInstanceId());
        }
    }

    private String name(UUID userId, UUID tenantId) {
        User u = userId == null ? null : recipients.activeUsers(List.of(userId), tenantId).get(userId);
        return u != null && u.getDisplayName() != null ? u.getDisplayName() : "someone";
    }
}
