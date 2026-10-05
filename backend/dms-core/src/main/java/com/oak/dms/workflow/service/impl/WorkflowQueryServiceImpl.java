package com.oak.dms.workflow.service.impl;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.dto.MyTaskCountResponse;
import com.oak.dms.workflow.dto.MyTaskResponse;
import com.oak.dms.workflow.dto.StageInstanceView;
import com.oak.dms.workflow.dto.TaskView;
import com.oak.dms.workflow.dto.UserRef;
import com.oak.dms.workflow.dto.WorkflowActionView;
import com.oak.dms.workflow.dto.WorkflowInstanceResponse;
import com.oak.dms.workflow.dto.WorkflowInstanceSummaryResponse;
import com.oak.dms.workflow.entity.WorkflowAction;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.entity.WorkflowTemplate;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.repository.WorkflowActionRepository;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowMembershipRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.repository.WorkflowTemplateRepository;
import com.oak.dms.workflow.service.ApproverResolver;
import com.oak.dms.workflow.service.WorkflowQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** See {@link WorkflowQueryService}. Loads in batches - one query per table, not per row. */
@Service
@RequiredArgsConstructor
public class WorkflowQueryServiceImpl implements WorkflowQueryService {

    /** Placeholder for an empty role-id list - Hibernate renders "in ()" unreliably. */
    private static final UUID NO_ID = new UUID(0L, 0L);
    private static final int COUNT_SCAN_LIMIT = 1000;

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowActionRepository actionRepository;
    private final WorkflowMembershipRepository membershipRepository;
    private final DocumentRepository documentRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final ApproverResolver approverResolver;
    private final AuthorizationService authorizationService;

    @Override
    @Transactional(readOnly = true)
    public WorkflowInstanceResponse getInstance(UUID instanceId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        WorkflowInstance instance = findOwned(instanceId, tenantId);
        List<WorkflowStageInstance> stages = stageInstanceRepository.findByInstanceIdOrderByStageOrderAsc(instanceId);
        List<WorkflowTask> tasks = stages.isEmpty() ? List.of() : taskRepository.findByStageInstanceIdInAndDeletedFalse(
                stages.stream().map(WorkflowStageInstance::getStageInstanceId).toList());
        requireVisible(instance, tasks, tenantId, userId);

        Map<UUID, WorkflowStage> stageDefs = new HashMap<>();
        stageRepository.findAllById(stages.stream().map(WorkflowStageInstance::getStageId).toList())
                .forEach(s -> stageDefs.put(s.getStageId(), s));
        Names names = names(tenantId, tasks, List.of(instance.getInitiatedBy()));
        Map<UUID, List<WorkflowTask>> tasksByStage = tasks.stream()
                .collect(Collectors.groupingBy(WorkflowTask::getStageInstanceId));

        List<StageInstanceView> stageViews = new ArrayList<>();
        for (WorkflowStageInstance si : stages) {
            WorkflowStage def = stageDefs.get(si.getStageId());
            List<TaskView> taskViews = tasksByStage.getOrDefault(si.getStageInstanceId(), List.of()).stream()
                    .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                    .map(t -> taskView(t, names))
                    .toList();
            stageViews.add(new StageInstanceView(si.getStageInstanceId(), si.getStageOrder(),
                    def == null ? null : def.getName(), si.getStatus().name(),
                    def == null ? null : def.getCompletionRule().name(), def == null ? null : def.getRejectionRule().name(),
                    si.getEntryCount(), si.getRequiredCount(), si.getApprovedCount(), si.getRejectedCount(),
                    si.getActivatedAt(), si.getDueAt(), si.getCompletedAt(), taskViews));
        }

        List<TaskView> nextAssignees = instance.getCurrentStageInstanceId() == null ? List.of()
                : tasksByStage.getOrDefault(instance.getCurrentStageInstanceId(), List.of()).stream()
                .filter(t -> TaskStatus.OPEN.contains(t.getStatus()))
                .map(t -> taskView(t, names))
                .toList();

        UUID myOpenTaskId = null;
        if (instance.getStatus() == InstanceStatus.in_progress && !userId.equals(instance.getInitiatedBy())
                && instance.getCurrentStageInstanceId() != null) {
            List<WorkflowTask> current = tasksByStage.getOrDefault(instance.getCurrentStageInstanceId(), List.of());
            boolean decided = current.stream().anyMatch(t -> userId.equals(t.getActedBy())
                    && (t.getStatus() == TaskStatus.approved || t.getStatus() == TaskStatus.rejected));
            if (!decided) {
                myOpenTaskId = current.stream()
                        .filter(t -> TaskStatus.OPEN.contains(t.getStatus()))
                        .filter(t -> approverResolver.canAct(tenantId, t, userId))
                        .map(WorkflowTask::getTaskId)
                        .findFirst().orElse(null);
            }
        }

        Document document = documentRepository.findById(instance.getDocumentId()).orElse(null);
        WorkflowTemplate template = templateRepository.findById(instance.getTemplateId()).orElse(null);
        return new WorkflowInstanceResponse(instance.getInstanceId(), instance.getDocumentId(),
                document == null ? null : document.getTitle(), instance.getDocumentVersionId(),
                document == null ? null : document.getLatestVersionId(), instance.isVersionChangedFlag(),
                instance.getTemplateId(), template == null ? null : template.getCode(),
                template == null ? null : template.getName(), instance.getTemplateVersionNumber(),
                instance.getStatus().name(), names.user(instance.getInitiatedBy()), instance.getStartedAt(),
                instance.getCompletedAt(), instance.getOutcomeReason(), instance.getCurrentStageInstanceId(),
                stageViews, nextAssignees, myOpenTaskId);
    }

    /** Gated at the controller by document-view / submit / audit-trail permissions. */
    @Override
    @Transactional(readOnly = true)
    public Page<WorkflowInstanceSummaryResponse> documentHistory(UUID documentId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        Page<WorkflowInstance> page = instanceRepository.findByTenantIdAndDocumentIdAndDeletedFalse(tenantId, documentId,
                pageable);
        Map<UUID, String> userNames = userNames(page.map(WorkflowInstance::getInitiatedBy).toList(), tenantId);
        Map<UUID, WorkflowTemplate> templates = new HashMap<>();
        templateRepository.findAllById(page.map(WorkflowInstance::getTemplateId).toSet())
                .forEach(t -> templates.put(t.getTemplateId(), t));
        return page.map(i -> new WorkflowInstanceSummaryResponse(i.getInstanceId(),
                templates.containsKey(i.getTemplateId()) ? templates.get(i.getTemplateId()).getName() : null,
                i.getTemplateVersionNumber(), i.getStatus().name(),
                new UserRef(i.getInitiatedBy(), userNames.get(i.getInitiatedBy())), i.getStartedAt(),
                i.getCompletedAt(), i.getOutcomeReason(), i.isVersionChangedFlag()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WorkflowActionView> timeline(UUID instanceId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        WorkflowInstance instance = findOwned(instanceId, tenantId);
        List<WorkflowStageInstance> stages = stageInstanceRepository.findByInstanceIdOrderByStageOrderAsc(instanceId);
        List<WorkflowTask> tasks = stages.isEmpty() ? List.of() : taskRepository.findByStageInstanceIdInAndDeletedFalse(
                stages.stream().map(WorkflowStageInstance::getStageInstanceId).toList());
        requireVisible(instance, tasks, tenantId, TenantContext.getUserId());

        Map<UUID, Integer> orders = stages.stream().collect(Collectors.toMap(WorkflowStageInstance::getStageInstanceId,
                WorkflowStageInstance::getStageOrder));
        Page<WorkflowAction> page = actionRepository.findByInstanceIdAndTenantId(instanceId, tenantId, pageable);
        Map<UUID, String> userNames = userNames(page.map(WorkflowAction::getActorUserId).toList(), tenantId);
        return page.map(a -> new WorkflowActionView(a.getActionId(), a.getActionType().name(),
                a.getActorUserId() == null ? null : new UserRef(a.getActorUserId(), userNames.get(a.getActorUserId())),
                a.getReason(), a.getDetail(), a.getStageInstanceId(),
                a.getStageInstanceId() == null ? null : orders.get(a.getStageInstanceId()), a.getTaskId(),
                a.getOccurredAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MyTaskResponse> myTasks(MyTasksView view, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        Page<WorkflowTask> page = queryMyTasks(tenantId, userId, view == null ? MyTasksView.all : view, pageable);
        return enrich(page, tenantId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public MyTaskCountResponse myTaskCount() {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        Page<WorkflowTask> page = queryMyTasks(tenantId, userId, MyTasksView.all, PageRequest.of(0, COUNT_SCAN_LIMIT));
        OffsetDateTime now = OffsetDateTime.now();
        long overdue = page.stream().filter(t -> t.getDueAt() != null && t.getDueAt().isBefore(now)).count();
        return new MyTaskCountResponse(page.getTotalElements(), overdue);
    }

    // ---------------------------------------------------------------- helpers

    private Page<WorkflowTask> queryMyTasks(UUID tenantId, UUID userId, MyTasksView view, Pageable pageable) {
        List<UUID> roleIds = new ArrayList<>(membershipRepository.findActiveRoleIds(tenantId, userId));
        if (roleIds.isEmpty()) {
            roleIds.add(NO_ID);
        }
        UUID departmentId = userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId)
                .map(User::getDepartmentId)
                .filter(id -> departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(id, tenantId)
                        .map(d -> d.getStatus() == Department.DepartmentStatus.active).orElse(false))
                .orElse(NO_ID);
        boolean includeUser = view == MyTasksView.all || view == MyTasksView.assigned;
        boolean includeClaimed = view != MyTasksView.available;
        boolean includePool = view == MyTasksView.all || view == MyTasksView.available;
        return taskRepository.findMyTasks(tenantId, userId, roleIds, departmentId, includeUser, includeClaimed,
                includePool, pageable);
    }

    private Page<MyTaskResponse> enrich(Page<WorkflowTask> page, UUID tenantId, UUID userId) {
        Map<UUID, WorkflowStageInstance> stageInstances = new HashMap<>();
        stageInstanceRepository.findAllById(page.map(WorkflowTask::getStageInstanceId).toSet())
                .forEach(si -> stageInstances.put(si.getStageInstanceId(), si));
        Set<UUID> instanceIds = stageInstances.values().stream().map(WorkflowStageInstance::getInstanceId)
                .collect(Collectors.toSet());
        Map<UUID, WorkflowInstance> instances = new HashMap<>();
        instanceRepository.findAllById(instanceIds).forEach(i -> instances.put(i.getInstanceId(), i));
        Map<UUID, Long> stageCounts = instanceIds.isEmpty() ? Map.of()
                : stageInstanceRepository.findByInstanceIdInOrderByStageOrderAsc(instanceIds).stream()
                .collect(Collectors.groupingBy(WorkflowStageInstance::getInstanceId, Collectors.counting()));
        Map<UUID, WorkflowStage> stageDefs = new HashMap<>();
        stageRepository.findAllById(stageInstances.values().stream().map(WorkflowStageInstance::getStageId).toList())
                .forEach(s -> stageDefs.put(s.getStageId(), s));
        Map<UUID, WorkflowTemplate> templates = new HashMap<>();
        templateRepository.findAllById(instances.values().stream().map(WorkflowInstance::getTemplateId).toList())
                .forEach(t -> templates.put(t.getTemplateId(), t));
        Map<UUID, Document> documents = new HashMap<>();
        documentRepository.findAllById(instances.values().stream().map(WorkflowInstance::getDocumentId).toList())
                .forEach(d -> documents.put(d.getDocumentId(), d));
        Map<UUID, String> typeNames = new HashMap<>();
        documents.values().stream().map(Document::getDocumentTypeId).distinct()
                .forEach(id -> documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(id, tenantId)
                        .ifPresent(t -> typeNames.put(id, t.getTypeName())));
        Names names = names(tenantId, page.getContent(),
                instances.values().stream().map(WorkflowInstance::getInitiatedBy).toList());
        OffsetDateTime now = OffsetDateTime.now();

        return page.map(t -> {
            WorkflowStageInstance si = stageInstances.get(t.getStageInstanceId());
            WorkflowInstance i = instances.get(si.getInstanceId());
            Document d = documents.get(i.getDocumentId());
            WorkflowTemplate tpl = templates.get(i.getTemplateId());
            WorkflowStage def = stageDefs.get(si.getStageId());
            return new MyTaskResponse(t.getTaskId(), i.getInstanceId(), i.getDocumentId(),
                    d == null ? null : d.getTitle(), d == null ? null : typeNames.get(d.getDocumentTypeId()),
                    tpl == null ? null : tpl.getName(), si.getStageOrder(), def == null ? null : def.getName(),
                    stageCounts.getOrDefault(i.getInstanceId(), 0L).intValue(), t.getAssigneeType().name(),
                    t.isPool() ? names.assignee(t) : null, t.getStatus().name(),
                    t.getStatus() == TaskStatus.claimed && userId.equals(t.getClaimedBy()), t.getDueAt(),
                    t.getDueAt() != null && t.getDueAt().isBefore(now), si.getActivatedAt(),
                    names.user(i.getInitiatedBy()), i.isVersionChangedFlag());
        });
    }

    private WorkflowInstance findOwned(UUID instanceId, UUID tenantId) {
        return instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
    }

    private void requireVisible(WorkflowInstance instance, List<WorkflowTask> tasks, UUID tenantId, UUID userId) {
        if (userId.equals(instance.getInitiatedBy())
                || authorizationService.hasAnyPermission(PermissionCodes.WA_AUDIT_TRAIL_VIEW,
                PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
            return;
        }
        for (WorkflowTask t : tasks) {
            if (userId.equals(t.getActedBy()) || userId.equals(t.getClaimedBy())) {
                return;
            }
        }
        boolean canActOnOpen = instance.getStatus() == InstanceStatus.in_progress && tasks.stream()
                .filter(t -> TaskStatus.OPEN.contains(t.getStatus()))
                .anyMatch(t -> approverResolver.canAct(tenantId, t, userId));
        if (!canActOnOpen) {
            throw new AccessDeniedException("Not a participant of workflow instance " + instance.getInstanceId());
        }
    }

    private static TaskView taskView(WorkflowTask t, Names names) {
        return new TaskView(t.getTaskId(), t.getAssigneeType().name(), t.getAssigneeId(), names.assignee(t),
                t.getStatus().name(), t.getClaimedBy() == null ? null : names.user(t.getClaimedBy()),
                t.getActedBy() == null ? null : names.user(t.getActedBy()),
                t.getDecision() == null ? null : t.getDecision().name(), t.getDecisionReason(), t.getActedAt(),
                t.getDueAt());
    }

    private Map<UUID, String> userNames(Collection<UUID> ids, UUID tenantId) {
        Map<UUID, String> names = new HashMap<>();
        Set<UUID> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        userRepository.findAllById(wanted).stream()
                .filter(u -> tenantId.equals(u.getTenantId()))
                .forEach(u -> names.put(u.getUserId(), u.getDisplayName()));
        return names;
    }

    /** Batch-resolved display names for the users, roles and departments a set of tasks mentions. */
    private Names names(UUID tenantId, Collection<WorkflowTask> tasks, Collection<UUID> extraUsers) {
        Set<UUID> users = new HashSet<>(extraUsers);
        Set<UUID> roles = new HashSet<>();
        Set<UUID> departments = new HashSet<>();
        for (WorkflowTask t : tasks) {
            users.add(t.getClaimedBy());
            users.add(t.getActedBy());
            switch (t.getAssigneeType()) {
                case user -> users.add(t.getAssigneeId());
                case role -> roles.add(t.getAssigneeId());
                case department -> departments.add(t.getAssigneeId());
            }
        }
        Map<UUID, String> userNames = userNames(users, tenantId);
        Map<UUID, String> roleNames = new HashMap<>();
        roleRepository.findAllById(roles).stream().filter(r -> tenantId.equals(r.getTenantId()))
                .forEach(r -> roleNames.put(r.getRoleId(), r.getRoleName()));
        Map<UUID, String> departmentNames = new HashMap<>();
        departmentRepository.findByDepartmentIdInAndTenantIdAndDeletedFalse(departments, tenantId)
                .forEach(d -> departmentNames.put(d.getDepartmentId(), d.getName()));
        return new Names(userNames, roleNames, departmentNames);
    }

    private record Names(Map<UUID, String> users, Map<UUID, String> roles, Map<UUID, String> departments) {
        UserRef user(UUID id) {
            return id == null ? null : new UserRef(id, users.get(id));
        }

        String assignee(WorkflowTask t) {
            return switch (t.getAssigneeType()) {
                case user -> users.get(t.getAssigneeId());
                case role -> roles.get(t.getAssigneeId());
                case department -> departments.get(t.getAssigneeId());
            };
        }
    }
}
