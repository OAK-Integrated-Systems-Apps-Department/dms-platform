package com.oak.dms.workflow.service.impl;

import com.oak.dms.department.repository.DepartmentRepository;
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
import com.oak.dms.workflow.helper.NotificationDispatcher;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer;
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
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1 workflow mail, the ThreatNotificationListener way: only AFTER
 * COMMIT (a rolled-back action never mails), off the request thread
 * (applicationTaskExecutor), and never failing anything - each recipient is
 * tried on its own and errors are logged ({@link NotificationDispatcher}).
 * Not retried: WA-NFR-004 ("not lost if offline") is met by the durable My
 * Tasks queue, not by the mail. Recipients are resolved here, after commit,
 * from the committed task rows ({@link TaskRouting#currentHolders}).
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
    private final TaskRouting taskRouting;
    private final WorkflowRecipients recipients;
    private final WorkflowNotificationMailer mailer;
    private final NotificationDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskAssigned(WorkflowTaskAssignedEvent event) {
        dispatcher.dispatch("task-assigned", event.instanceId(), () -> notifyTaskAssigned(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutcome(WorkflowOutcomeEvent event) {
        dispatcher.dispatch("outcome", event.instanceId(), () -> notifyOutcome(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStageUnassignable(WorkflowStageUnassignableEvent event) {
        dispatcher.dispatch("unassignable", event.instanceId(), () -> notifyUnassignable(event));
    }

    private void notifyTaskAssigned(WorkflowTaskAssignedEvent event) {
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        if (instance == null) {
            return;
        }
        String title = recipients.documentTitle(instance.getDocumentId());
        for (WorkflowTask task : taskRepository.findAllById(event.taskIds())) {
            WorkflowStageInstance si = stageInstanceRepository.findById(task.getStageInstanceId()).orElse(null);
            WorkflowStage stage = si == null ? null : stageRepository.findById(si.getStageId()).orElse(null);
            if (stage == null) {
                continue;
            }
            Set<UUID> to = taskRouting.currentHolders(event.tenantId(), task, instance.getInitiatedBy());
            String poolName = task.isPool() ? poolName(task, event.tenantId()) : null;
            for (User user : recipients.activeUsers(to, event.tenantId()).values()) {
                NotificationDispatcher.send(() -> mailer.sendTaskAssigned(user, title, stage.getName(), poolName,
                        task.getDueAt(), instance.getDocumentId()), "task-assigned", user, instance.getInstanceId());
            }
        }
    }

    private void notifyOutcome(WorkflowOutcomeEvent event) {
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        if (instance == null || instance.getStatus() == InstanceStatus.in_progress) {
            return;
        }
        if (instance.getStatus() == InstanceStatus.cancelled && instance.getInitiatedBy().equals(event.actorUserId())) {
            return; // they withdrew it themselves
        }
        User initiator = recipients.activeUser(instance.getInitiatedBy(), event.tenantId());
        if (initiator == null) {
            return;
        }
        String outcome = instance.getStatus().name();
        NotificationDispatcher.send(() -> mailer.sendOutcome(initiator, recipients.documentTitle(instance.getDocumentId()),
                outcome, instance.getOutcomeReason(), instance.getInstanceId()), "outcome", initiator,
                instance.getInstanceId());
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
        String title = recipients.documentTitle(instance.getDocumentId());
        for (User user : recipients.activeUsers(ids, event.tenantId()).values()) {
            NotificationDispatcher.send(() -> mailer.sendUnassignable(user, title, stage.getName(),
                    instance.getInstanceId()), "unassignable", user, instance.getInstanceId());
        }
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
