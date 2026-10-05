package com.oak.dms.workflow.service.impl;

import com.oak.dms.common.exception.KeycloakIntegrationException;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.config.WorkflowProperties;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowStageParticipant;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.event.WorkflowOutcomeEvent;
import com.oak.dms.workflow.event.WorkflowStageUnassignableEvent;
import com.oak.dms.workflow.event.WorkflowTaskAssignedEvent;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageParticipantRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.ApproverResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1 workflow mail, the ThreatNotificationListener way: only AFTER
 * COMMIT (a rolled-back action never mails), off the request thread
 * (applicationTaskExecutor), and never failing anything - each recipient is
 * tried on its own and errors are logged. Not retried: WA-NFR-004 ("not lost
 * if offline") is met by the durable My Tasks queue, not by the mail.
 * Recipients are resolved here, after commit, from the committed rows.
 */
@Slf4j
@Component
public class WorkflowNotificationListener {

    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowStageParticipantRepository participantRepository;
    private final WorkflowTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final KeycloakUserService keycloakUserService;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final ApproverResolver approverResolver;
    private final WorkflowNotificationMailer mailer;
    private final WorkflowProperties properties;
    private final TaskExecutor taskExecutor;

    public WorkflowNotificationListener(WorkflowInstanceRepository instanceRepository,
                                        WorkflowStageInstanceRepository stageInstanceRepository,
                                        WorkflowStageRepository stageRepository,
                                        WorkflowStageParticipantRepository participantRepository,
                                        WorkflowTaskRepository taskRepository,
                                        DocumentRepository documentRepository,
                                        UserRepository userRepository,
                                        RolePermissionRepository rolePermissionRepository,
                                        KeycloakUserService keycloakUserService,
                                        RoleRepository roleRepository,
                                        DepartmentRepository departmentRepository,
                                        ApproverResolver approverResolver,
                                        WorkflowNotificationMailer mailer,
                                        WorkflowProperties properties,
                                        @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.instanceRepository = instanceRepository;
        this.stageInstanceRepository = stageInstanceRepository;
        this.stageRepository = stageRepository;
        this.participantRepository = participantRepository;
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.keycloakUserService = keycloakUserService;
        this.roleRepository = roleRepository;
        this.departmentRepository = departmentRepository;
        this.approverResolver = approverResolver;
        this.mailer = mailer;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskAssigned(WorkflowTaskAssignedEvent event) {
        dispatch("task-assigned", event.instanceId(), () -> notifyTaskAssigned(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutcome(WorkflowOutcomeEvent event) {
        dispatch("outcome", event.instanceId(), () -> notifyOutcome(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStageUnassignable(WorkflowStageUnassignableEvent event) {
        dispatch("unassignable", event.instanceId(), () -> notifyUnassignable(event));
    }

    private void dispatch(String kind, UUID instanceId, Runnable work) {
        if (!properties.getNotifications().isEnabled()) {
            return;
        }
        try {
            taskExecutor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    log.warn("Workflow {} notification for instance {} failed: {}", kind, instanceId, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not schedule workflow {} notification for instance {}: {}", kind, instanceId, e.getMessage());
        }
    }

    private void notifyTaskAssigned(WorkflowTaskAssignedEvent event) {
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        if (instance == null) {
            return;
        }
        String title = documentTitle(instance);
        for (WorkflowTask task : taskRepository.findAllById(event.taskIds())) {
            WorkflowStageInstance si = stageInstanceRepository.findById(task.getStageInstanceId()).orElse(null);
            WorkflowStage stage = si == null ? null : stageRepository.findById(si.getStageId()).orElse(null);
            WorkflowStageParticipant participant = participantRepository.findById(task.getParticipantId()).orElse(null);
            if (stage == null || participant == null) {
                continue;
            }
            Set<UUID> recipients = approverResolver.resolve(event.tenantId(), participant, instance.getInitiatedBy());
            String poolName = task.isPool() ? poolName(participant, event.tenantId()) : null;
            for (User user : activeUsers(recipients, event.tenantId()).values()) {
                send(() -> mailer.sendTaskAssigned(user, title, stage.getName(), poolName, task.getDueAt(),
                        instance.getDocumentId()), "task-assigned", user, instance);
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
        User initiator = activeUsers(Set.of(instance.getInitiatedBy()), event.tenantId()).get(instance.getInitiatedBy());
        if (initiator == null) {
            return;
        }
        String outcome = instance.getStatus().name();
        send(() -> mailer.sendOutcome(initiator, documentTitle(instance), outcome, instance.getOutcomeReason(),
                instance.getInstanceId()), "outcome", initiator, instance);
    }

    private void notifyUnassignable(WorkflowStageUnassignableEvent event) {
        WorkflowInstance instance = instanceRepository.findById(event.instanceId()).orElse(null);
        WorkflowStageInstance si = stageInstanceRepository.findById(event.stageInstanceId()).orElse(null);
        WorkflowStage stage = si == null ? null : stageRepository.findById(si.getStageId()).orElse(null);
        if (instance == null || stage == null) {
            return;
        }
        Set<UUID> ids = new LinkedHashSet<>(
                rolePermissionRepository.findUserIdsWithPermission(event.tenantId(), PermissionCodes.WA_TASK_REASSIGN));
        ids.add(instance.getInitiatedBy());
        try {
            for (UUID keycloakId : keycloakUserService.listRealmRoleMemberIds(TENANT_ADMIN_ROLE)) {
                userRepository.findByKeycloakIdAndDeletedFalse(keycloakId)
                        .filter(u -> event.tenantId().equals(u.getTenantId()))
                        .ifPresent(u -> ids.add(u.getUserId()));
            }
        } catch (KeycloakIntegrationException e) {
            log.warn("Could not list tenant admins from Keycloak for an unassignable-stage notification: {}", e.getMessage());
        }
        String title = documentTitle(instance);
        for (User user : activeUsers(ids, event.tenantId()).values()) {
            send(() -> mailer.sendUnassignable(user, title, stage.getName(), instance.getInstanceId()),
                    "unassignable", user, instance);
        }
    }

    private Map<UUID, User> activeUsers(Set<UUID> ids, UUID tenantId) {
        Map<UUID, User> users = new LinkedHashMap<>();
        userRepository.findAllById(ids).stream()
                .filter(u -> tenantId.equals(u.getTenantId()) && !u.isDeleted() && u.getStatus() == User.UserStatus.active)
                .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                .forEach(u -> users.put(u.getUserId(), u));
        return users;
    }

    private String documentTitle(WorkflowInstance instance) {
        return documentRepository.findById(instance.getDocumentId()).map(Document::getTitle).orElse("a document");
    }

    private String poolName(WorkflowStageParticipant participant, UUID tenantId) {
        return switch (participant.getAssigneeType()) {
            case role -> roleRepository.findByRoleIdAndTenantIdAndDeletedFalse(participant.getAssigneeId(), tenantId)
                    .map(r -> "the " + r.getRoleName() + " role").orElse("your role");
            case department -> departmentRepository
                    .findByDepartmentIdAndTenantIdAndDeletedFalse(participant.getAssigneeId(), tenantId)
                    .map(d -> "the " + d.getName() + " department").orElse("your department");
            case user -> null;
        };
    }

    private static void send(Runnable sender, String kind, User to, WorkflowInstance instance) {
        try {
            sender.run();
            log.info("Workflow {} mail for instance {} sent to user {}", kind, instance.getInstanceId(), to.getUserId());
        } catch (RuntimeException e) {
            log.warn("Workflow {} mail for instance {} to user {} failed: {}", kind, instance.getInstanceId(),
                    to.getUserId(), e.getMessage());
        }
    }
}
