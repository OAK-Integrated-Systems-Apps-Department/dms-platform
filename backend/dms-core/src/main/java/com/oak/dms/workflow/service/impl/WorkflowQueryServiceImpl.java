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
import com.oak.dms.workflow.dto.InfoRequestView;
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
import com.oak.dms.workflow.enums.InfoRequestStatus;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.StageInstanceStatus;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowEvidenceMapper;
import com.oak.dms.workflow.repository.WorkflowActionRepository;
import com.oak.dms.workflow.repository.WorkflowInfoRequestRepository;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowMembershipRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageParticipantRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowSupportingDocumentRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.repository.WorkflowTemplateRepository;
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

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowStageParticipantRepository participantRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowActionRepository actionRepository;
    private final WorkflowMembershipRepository membershipRepository;
    private final DocumentRepository documentRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final TaskRouting taskRouting;
    private final WorkflowEvidenceMapper evidenceMapper;
    private final WorkflowInfoRequestRepository infoRequestRepository;
    private final WorkflowSupportingDocumentRepository supportingDocumentRepository;
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
        Map<UUID, Long> openRequests = openRequestCounts(tenantId, tasks);
        Map<UUID, List<WorkflowTask>> tasksByStage = tasks.stream()
                .collect(Collectors.groupingBy(WorkflowTask::getStageInstanceId));

        Map<UUID, Integer> plannedEntries = plannedEntryCounts(tenantId, stages);

        List<StageInstanceView> stageViews = new ArrayList<>();
        for (WorkflowStageInstance si : stages) {
            WorkflowStage def = stageDefs.get(si.getStageId());
            int entryCount = neverActivated(si)
                    ? plannedEntries.getOrDefault(si.getStageId(), 0) : si.getEntryCount();
            List<TaskView> taskViews = tasksByStage.getOrDefault(si.getStageInstanceId(), List.of()).stream()
                    .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                    .map(t -> taskView(t, names, openRequests))
                    .toList();
            stageViews.add(new StageInstanceView(si.getStageInstanceId(), si.getStageOrder(),
                    def == null ? null : def.getName(), si.getStatus().name(),
                    def == null ? null : def.getCompletionRule().name(), def == null ? null : def.getRejectionRule().name(),
                    entryCount, si.getRequiredCount(), si.getApprovedCount(), si.getRejectedCount(),
                    si.getActivatedAt(), si.getDueAt(), si.getCompletedAt(), taskViews,
                    def == null || def.isAllowInfoRequests(), def != null && def.isRequiresSignature()));
        }

        List<TaskView> nextAssignees = instance.getCurrentStageInstanceId() == null ? List.of()
                : tasksByStage.getOrDefault(instance.getCurrentStageInstanceId(), List.of()).stream()
                .filter(t -> TaskStatus.OPEN.contains(t.getStatus()))
                .map(t -> taskView(t, names, openRequests))
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
                        .filter(t -> taskRouting.canAct(tenantId, t, userId))
                        .map(WorkflowTask::getTaskId)
                        .findFirst().orElse(null);
            }
        }

        List<InfoRequestView> requests = evidenceMapper.requestViews(tenantId, infoRequestRepository
                .findByInstanceIdAndTenantIdAndDeletedFalseOrderByRequestedAtAsc(instanceId, tenantId));
        Document document = documentRepository.findById(instance.getDocumentId()).orElse(null);
        WorkflowTemplate template = templateRepository.findById(instance.getTemplateId()).orElse(null);
        return new WorkflowInstanceResponse(instance.getInstanceId(), instance.getDocumentId(),
                document == null ? null : document.getTitle(), instance.getDocumentVersionId(),
                document == null ? null : document.getLatestVersionId(), instance.isVersionChangedFlag(),
                instance.getTemplateId(), template == null ? null : template.getCode(),
                template == null ? null : template.getName(), instance.getTemplateVersionNumber(),
                instance.getStatus().name(), names.user(instance.getInitiatedBy()), instance.getStartedAt(),
                instance.getCompletedAt(), instance.getOutcomeReason(), instance.getCurrentStageInstanceId(),
                stageViews, nextAssignees, myOpenTaskId, requests,
                evidenceMapper.supportingViews(tenantId, supportingDocumentRepository
                        .findByInstanceIdAndTenantIdAndDeletedFalseOrderByAddedAtAsc(instanceId, tenantId)),
                requests.stream().filter(r -> "open".equals(r.status()) && userId.equals(r.requestedFrom().userId()))
                        .map(InfoRequestView::infoRequestId).toList());
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
        Queue q = queue(tenantId, userId, MyTasksView.all);
        long open = taskRepository.findMyTasks(tenantId, userId, q.roleIds, q.departmentId, q.user, q.claimed, q.pool,
                q.delegated, q.escalated, q.waitingOnly, PageRequest.of(0, 1)).getTotalElements();
        long overdue = taskRepository.countMyOverdueTasks(tenantId, userId, q.roleIds, q.departmentId, q.user,
                q.claimed, q.pool, q.delegated, q.escalated, q.waitingOnly, OffsetDateTime.now());
        long requestsForMe = infoRequestRepository.countByTenantIdAndRequestedFromAndStatusAndDeletedFalse(tenantId,
                userId, InfoRequestStatus.open);
        return new MyTaskCountResponse(open, overdue, requestsForMe);
    }

    // ---------------------------------------------------------------- helpers

    private Page<WorkflowTask> queryMyTasks(UUID tenantId, UUID userId, MyTasksView view, Pageable pageable) {
        Queue q = queue(tenantId, userId, view);
        return taskRepository.findMyTasks(tenantId, userId, q.roleIds, q.departmentId, q.user, q.claimed, q.pool,
                q.delegated, q.escalated, q.waitingOnly, pageable);
    }

    /** The caller's pools and which My Tasks routes a view includes (mirrors TaskRouting). */
    private record Queue(List<UUID> roleIds, UUID departmentId, boolean user, boolean claimed, boolean pool,
                         boolean delegated, boolean escalated, boolean waitingOnly) {
    }

    private Queue queue(UUID tenantId, UUID userId, MyTasksView view) {
        List<UUID> roleIds = new ArrayList<>(membershipRepository.findActiveRoleIds(tenantId, userId));
        if (roleIds.isEmpty()) {
            roleIds.add(NO_ID);
        }
        UUID departmentId = userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId)
                .map(User::getDepartmentId)
                .filter(id -> departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(id, tenantId)
                        .map(d -> d.getStatus() == Department.DepartmentStatus.active).orElse(false))
                .orElse(NO_ID);
        boolean mine = view != MyTasksView.available && view != MyTasksView.claimed;
        boolean pool = view == MyTasksView.all || view == MyTasksView.available;
        boolean claimed = view != MyTasksView.available;
        boolean escalated = view == MyTasksView.all || view == MyTasksView.assigned;
        return new Queue(roleIds, departmentId, mine, claimed, pool, mine, escalated, view == MyTasksView.waiting);
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
        Map<UUID, Long> openRequests = openRequestCounts(tenantId, page.getContent());

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
                    TaskStatus.HELD.contains(t.getStatus()) && userId.equals(t.getClaimedBy())
                            && t.getDelegatedTo() == null,
                    t.getDueAt(), t.getStatus() != TaskStatus.awaiting_info && t.getDueAt() != null
                            && t.getDueAt().isBefore(now), si.getActivatedAt(),
                    names.user(i.getInitiatedBy()), i.isVersionChangedFlag(), routeName(tenantId, t, userId),
                    t.getDelegatedFrom() == null ? null : names.user(t.getDelegatedFrom()),
                    openRequests.getOrDefault(t.getTaskId(), 0L), def != null && def.isRequiresSignature());
        });
    }

    /**
     * A stage instance that never activated keeps entry_count 0, so the
     * status view takes its entry count from the template stage instead
     * (otherwise a skipped stage reads "Any of 0 entries").
     */
    private Map<UUID, Integer> plannedEntryCounts(UUID tenantId, List<WorkflowStageInstance> stages) {
        List<UUID> stageIds = stages.stream().filter(WorkflowQueryServiceImpl::neverActivated)
                .map(WorkflowStageInstance::getStageId).toList();
        Map<UUID, Integer> counts = new HashMap<>();
        if (!stageIds.isEmpty()) {
            for (Object[] row : participantRepository.countLiveByStageIds(tenantId, stageIds)) {
                counts.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        return counts;
    }

    private static boolean neverActivated(WorkflowStageInstance si) {
        return si.getStatus() == StageInstanceStatus.not_started
                || (si.getStatus() == StageInstanceStatus.skipped && si.getActivatedAt() == null);
    }

    private WorkflowInstance findOwned(UUID instanceId, UUID tenantId) {
        return instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireVisible(UUID instanceId) {
        UUID tenantId = TenantContext.getTenantId();
        WorkflowInstance instance = findOwned(instanceId, tenantId);
        List<WorkflowStageInstance> stages = stageInstanceRepository.findByInstanceIdOrderByStageOrderAsc(instanceId);
        List<WorkflowTask> tasks = stages.isEmpty() ? List.of() : taskRepository.findByStageInstanceIdInAndDeletedFalse(
                stages.stream().map(WorkflowStageInstance::getStageInstanceId).toList());
        requireVisible(instance, tasks, tenantId, TenantContext.getUserId());
    }

    private void requireVisible(WorkflowInstance instance, List<WorkflowTask> tasks, UUID tenantId, UUID userId) {
        if (userId.equals(instance.getInitiatedBy())
                || authorizationService.hasAnyPermission(PermissionCodes.WA_AUDIT_TRAIL_VIEW,
                PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
            return;
        }
        for (WorkflowTask t : tasks) {
            if (userId.equals(t.getActedBy()) || userId.equals(t.getClaimedBy())
                    || userId.equals(t.getDelegatedTo()) || userId.equals(t.getDelegatedFrom())
                    || userId.equals(t.getEscalatedTo())) {
                return;
            }
        }
        // Phase 2 §9: someone asked for information, or who attached evidence, sees the run.
        if (infoRequestRepository.existsByInstanceIdAndRequestedFromAndDeletedFalse(instance.getInstanceId(), userId)
                || supportingDocumentRepository.existsByInstanceIdAndAddedByAndDeletedFalse(instance.getInstanceId(),
                userId)) {
            return;
        }
        boolean canActOnOpen = instance.getStatus() == InstanceStatus.in_progress && tasks.stream()
                .filter(t -> TaskStatus.OPEN.contains(t.getStatus()))
                .anyMatch(t -> taskRouting.canAct(tenantId, t, userId));
        if (!canActOnOpen) {
            throw new AccessDeniedException("Not a participant of workflow instance " + instance.getInstanceId());
        }
    }

    /** Open information requests per task, in one grouped query. */
    private Map<UUID, Long> openRequestCounts(UUID tenantId, Collection<WorkflowTask> tasks) {
        List<UUID> waiting = tasks.stream().filter(t -> t.getStatus() == TaskStatus.awaiting_info)
                .map(WorkflowTask::getTaskId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        if (!waiting.isEmpty()) {
            for (Object[] row : infoRequestRepository.countOpenByTaskIds(tenantId, waiting)) {
                counts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return counts;
    }

    private static TaskView taskView(WorkflowTask t, Names names, Map<UUID, Long> openRequests) {
        return new TaskView(t.getTaskId(), t.getAssigneeType().name(), t.getAssigneeId(), names.assignee(t),
                t.getStatus().name(), t.getClaimedBy() == null ? null : names.user(t.getClaimedBy()),
                t.getActedBy() == null ? null : names.user(t.getActedBy()),
                t.getDecision() == null ? null : t.getDecision().name(), t.getDecisionReason(), t.getActedAt(),
                t.getDueAt(), names.user(t.getDelegatedFrom()), names.user(t.getDelegatedTo()), t.getDelegatedAt(),
                names.user(t.getEscalatedTo()), t.getEscalatedAt(), t.getReminderSentAt(), t.getSlaPausedAt(),
                t.getSlaPausedSeconds(), openRequests.getOrDefault(t.getTaskId(), 0L));
    }

    /** How the caller reaches a task in their queue; "pool" when only the live pool check would say so. */
    private String routeName(UUID tenantId, WorkflowTask t, UUID userId) {
        TaskRouting.Route route = taskRouting.route(tenantId, t, userId);
        return route == null ? null : route.name();
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
            users.add(t.getDelegatedFrom());
            users.add(t.getDelegatedTo());
            users.add(t.getEscalatedTo());
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
