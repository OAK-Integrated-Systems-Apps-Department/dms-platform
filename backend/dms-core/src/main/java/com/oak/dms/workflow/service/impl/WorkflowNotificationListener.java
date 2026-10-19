package com.oak.dms.workflow.service.impl;

import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.event.WorkflowOutcomeEvent;
import com.oak.dms.workflow.event.WorkflowStageUnassignableEvent;
import com.oak.dms.workflow.event.WorkflowTaskAssignedEvent;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer.Doc;
import com.oak.dms.workflow.helper.WorkflowRecipients;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1 workflow notifications, written through the shared notification
 * service (3.2, N8). They run BEFORE_COMMIT, inside the transaction that
 * raised the event, so the inbox entry and its delivery rows commit atomically
 * with the action (a rolled-back action leaves nothing behind, a committed one
 * never loses its notification); email goes out later via the dispatcher, with
 * retries. WA-NFR-004 ("not lost if offline") is met by the durable inbox as
 * well as the My Tasks queue. Recipients are resolved from the task rows
 * ({@link TaskRouting#currentHolders}) and each event's recipients go out in
 * one call (one access check for all of them).
 */
@Component
@RequiredArgsConstructor
public class WorkflowNotificationListener {

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowTaskRepository taskRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final DocumentRepository documentRepository;
    private final TaskRouting taskRouting;
    private final WorkflowRecipients recipients;
    private final WorkflowNotificationMailer mailer;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTaskAssigned(WorkflowTaskAssignedEvent event) {
        notifyTaskAssigned(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOutcome(WorkflowOutcomeEvent event) {
        notifyOutcome(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onStageUnassignable(WorkflowStageUnassignableEvent event) {
        notifyUnassignable(event);
    }

    private void notifyTaskAssigned(WorkflowTaskAssignedEvent event) {
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        if (instance == null) {
            return;
        }
        Doc doc = new Doc(instance.getDocumentId(), recipients.documentTitle(instance.getDocumentId()));
        for (WorkflowTask task : taskRepository.findAllById(event.taskIds())) {
            WorkflowStageInstance si = stageInstanceRepository.findById(task.getStageInstanceId()).orElse(null);
            WorkflowStage stage = si == null ? null : stageRepository.findById(si.getStageId()).orElse(null);
            if (stage == null) {
                continue;
            }
            Set<UUID> to = taskRouting.currentHolders(event.tenantId(), task, instance.getInitiatedBy());
            String poolName = task.isPool() ? poolName(task, event.tenantId()) : null;
            mailer.sendTaskAssigned(recipients.activeUsers(to, event.tenantId()).values(), doc, stage.getName(),
                    poolName, task.getDueAt());
        }
    }

    private void notifyOutcome(WorkflowOutcomeEvent event) {
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        if (instance == null || instance.getStatus() == InstanceStatus.in_progress) {
            return;
        }
        Doc doc = new Doc(instance.getDocumentId(), recipients.documentTitle(instance.getDocumentId()));
        String outcome = instance.getStatus().name();
        boolean withdrewIt = instance.getStatus() == InstanceStatus.cancelled
                && instance.getInitiatedBy().equals(event.actorUserId());
        if (!withdrewIt) {
            User initiator = recipients.activeUser(instance.getInitiatedBy(), event.tenantId());
            if (initiator != null) {
                mailer.sendOutcome(List.of(initiator), doc, outcome, instance.getOutcomeReason(),
                        instance.getInstanceId());
            }
        }
        notifyOwnerOfStatusChange(event, instance, doc, outcome);
    }

    /**
     * The document's status changed (N10): its owner hears of an approval outcome
     * unless they started it (they get the outcome notification) or caused it.
     * Optional tier - in-app by default, email only if the owner or the tenant
     * opts in. (There is no standalone document status; workflow outcomes are
     * the status changes the system has.)
     */
    private void notifyOwnerOfStatusChange(WorkflowOutcomeEvent event, WorkflowInstance instance, Doc doc,
                                           String outcome) {
        UUID ownerId = documentRepository.findById(instance.getDocumentId())
                .filter(d -> event.tenantId().equals(d.getTenantId()))
                .map(d -> d.getOwnerUserId()).orElse(null);
        if (ownerId == null || ownerId.equals(instance.getInitiatedBy()) || ownerId.equals(event.actorUserId())) {
            return;
        }
        User owner = recipients.activeUser(ownerId, event.tenantId());
        if (owner != null) {
            mailer.sendStatusChanged(List.of(owner), doc, outcome, instance.getInstanceId());
        }
    }

    private void notifyUnassignable(WorkflowStageUnassignableEvent event) {
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        WorkflowStageInstance si = stageInstanceRepository.findById(event.stageInstanceId()).orElse(null);
        WorkflowStage stage = si == null ? null : stageRepository.findById(si.getStageId()).orElse(null);
        if (instance == null || stage == null) {
            return;
        }
        Set<UUID> ids = new LinkedHashSet<>(recipients.reassignersAndAdmins(event.tenantId()));
        ids.add(instance.getInitiatedBy());
        Doc doc = new Doc(instance.getDocumentId(), recipients.documentTitle(instance.getDocumentId()));
        mailer.sendUnassignable(recipients.activeUsers(ids, event.tenantId()).values(), doc, stage.getName(),
                instance.getInstanceId());
    }

    private String poolName(WorkflowTask task, UUID tenantId) {
        return switch (task.getAssigneeType()) {
            case role -> roleRepository.findByRoleIdAndTenantIdAndDeletedFalse(task.getAssigneeId(), tenantId)
                    .map(r -> "the " + r.getRoleName() + " role").orElse("your role");
            case department -> departmentRepository
                    .findByDepartmentIdAndTenantIdAndDeletedFalse(task.getAssigneeId(), tenantId)
                    .map(d -> "the " + d.getName() + " department").orElse("your department");
            case user -> null;
        };
    }
}
