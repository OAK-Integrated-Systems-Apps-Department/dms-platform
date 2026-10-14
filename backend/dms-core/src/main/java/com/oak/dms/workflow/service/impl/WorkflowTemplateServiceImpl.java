package com.oak.dms.workflow.service.impl;

import com.oak.dms.common.sql.LikePatterns;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.dto.CreateWorkflowTemplateRequest;
import com.oak.dms.workflow.dto.ParticipantRequest;
import com.oak.dms.workflow.dto.ParticipantResponse;
import com.oak.dms.workflow.dto.ReorderStagesRequest;
import com.oak.dms.workflow.dto.UpdateWorkflowTemplateRequest;
import com.oak.dms.workflow.dto.UpsertWorkflowStageRequest;
import com.oak.dms.workflow.dto.WorkflowStageResponse;
import com.oak.dms.workflow.dto.WorkflowTemplateResponse;
import com.oak.dms.workflow.dto.WorkflowTemplateSummaryResponse;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowStageParticipant;
import com.oak.dms.workflow.entity.WorkflowTemplate;
import com.oak.dms.workflow.enums.AssigneeType;
import com.oak.dms.workflow.enums.EscalationTarget;
import com.oak.dms.workflow.enums.OnDocumentChange;
import com.oak.dms.workflow.enums.TemplateStatus;
import com.oak.dms.workflow.exception.WorkflowStateException;
import com.oak.dms.workflow.exception.WorkflowValidationException;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageParticipantRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.repository.WorkflowTemplateRepository;
import com.oak.dms.workflow.service.WorkflowTemplateService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** See {@link WorkflowTemplateService}. Stage edits lock the template row, so two builders serialise. */
@Service
@RequiredArgsConstructor
public class WorkflowTemplateServiceImpl implements WorkflowTemplateService {

    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowStageParticipantRepository participantRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;

    // ------------------------------------------------------------------ read

    @Override
    @Transactional(readOnly = true)
    public Page<WorkflowTemplateSummaryResponse> list(TemplateStatus status, UUID documentTypeId, String search,
                                                      Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        String pattern = search == null || search.isBlank() ? null : LikePatterns.contains(search.trim());
        Specification<WorkflowTemplate> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("tenantId"), tenantId));
            p.add(cb.isFalse(root.get("deleted")));
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (documentTypeId != null) {
                p.add(cb.equal(root.get("documentTypeId"), documentTypeId));
            }
            if (pattern != null) {
                p.add(cb.or(cb.like(cb.lower(root.get("name")), pattern), cb.like(cb.lower(root.get("code")), pattern)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<WorkflowTemplate> page = templateRepository.findAll(spec, pageable);
        Map<UUID, String> typeNames = documentTypeNames(page.map(WorkflowTemplate::getDocumentTypeId).toList(), tenantId);
        return page.map(t -> toSummary(t, typeNames.get(t.getDocumentTypeId())));
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowTemplateResponse get(UUID templateId) {
        return toResponse(findOwned(templateId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowTemplateSummaryResponse> versions(UUID templateId) {
        WorkflowTemplate template = findOwned(templateId);
        List<WorkflowTemplate> all = templateRepository
                .findByTenantIdAndCodeAndDeletedFalseOrderByVersionNumberDesc(template.getTenantId(), template.getCode());
        Map<UUID, String> typeNames = documentTypeNames(all.stream().map(WorkflowTemplate::getDocumentTypeId).toList(),
                template.getTenantId());
        return all.stream().map(t -> toSummary(t, typeNames.get(t.getDocumentTypeId()))).toList();
    }

    // ------------------------------------------------------------- templates

    @Override
    @Transactional
    public WorkflowTemplateResponse create(CreateWorkflowTemplateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (templateRepository.existsByTenantIdAndCodeAndDeletedFalse(tenantId, code)) {
            throw new WorkflowStateException("WORKFLOW_TEMPLATE_CODE_EXISTS",
                    "A workflow template with code " + code + " already exists - create a new version of it instead");
        }
        requireActiveDocumentType(request.documentTypeId(), tenantId);

        WorkflowTemplate saved = templateRepository.saveAndFlush(WorkflowTemplate.builder()
                .tenantId(tenantId)
                .documentTypeId(request.documentTypeId())
                .name(request.name().trim())
                .code(code)
                .description(blankToNull(request.description()))
                .versionNumber(1)
                .status(TemplateStatus.draft)
                .onDocumentChange(request.onDocumentChange() == null ? OnDocumentChange.flag_only : request.onDocumentChange())
                .createdBy(TenantContext.getUserId())
                .build());
        audit(AuditActionCatalog.WORKFLOW_TEMPLATE_CREATED, saved).newValue(snapshot(saved)).log();
        return toResponse(saved);
    }

    @Override
    @Transactional
    public WorkflowTemplateResponse update(UUID templateId, UpdateWorkflowTemplateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        WorkflowTemplate template = lockDraft(templateId);
        Map<String, Object> before = snapshot(template);
        if (!template.getDocumentTypeId().equals(request.documentTypeId())) {
            if (template.getVersionNumber() > 1) {
                throw new WorkflowValidationException("WORKFLOW_DOCUMENT_TYPE_LOCKED",
                        "Only version 1 of a template can change its document type");
            }
            requireActiveDocumentType(request.documentTypeId(), tenantId);
            template.setDocumentTypeId(request.documentTypeId());
        }
        template.setName(request.name().trim());
        template.setDescription(blankToNull(request.description()));
        template.setOnDocumentChange(request.onDocumentChange());
        touch(template);
        templateRepository.save(template);
        audit(AuditActionCatalog.WORKFLOW_TEMPLATE_UPDATED, template)
                .previousValue(before)
                .newValue(snapshot(template))
                .log();
        return toResponse(template);
    }

    @Override
    @Transactional
    public WorkflowTemplateResponse createVersion(UUID templateId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        WorkflowTemplate source = templateRepository.lockByTemplateIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", templateId));
        if (templateRepository.existsByTenantIdAndCodeAndStatusAndDeletedFalse(tenantId, source.getCode(),
                TemplateStatus.draft)) {
            throw new WorkflowStateException("WORKFLOW_DRAFT_EXISTS",
                    "Template " + source.getCode() + " already has a draft version - edit that one");
        }
        int nextVersion = templateRepository.findMaxVersionNumber(tenantId, source.getCode()) + 1;
        WorkflowTemplate copy;
        try {
            copy = templateRepository.saveAndFlush(WorkflowTemplate.builder()
                    .tenantId(tenantId)
                    .documentTypeId(source.getDocumentTypeId())
                    .name(source.getName())
                    .code(source.getCode())
                    .description(source.getDescription())
                    .versionNumber(nextVersion)
                    .status(TemplateStatus.draft)
                    .onDocumentChange(source.getOnDocumentChange())
                    .createdBy(userId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new WorkflowStateException("WORKFLOW_DRAFT_EXISTS",
                    "Another draft version of " + source.getCode() + " was just created");
        }

        List<WorkflowStage> stages = liveStages(source);
        Map<UUID, List<WorkflowStageParticipant>> participants = participantsByStage(stages);
        for (WorkflowStage stage : stages) {
            WorkflowStage stageCopy = stageRepository.save(WorkflowStage.builder()
                    .tenantId(tenantId)
                    .templateId(copy.getTemplateId())
                    .stageOrder(stage.getStageOrder())
                    .name(stage.getName())
                    .completionRule(stage.getCompletionRule())
                    .rejectionRule(stage.getRejectionRule())
                    .slaHours(stage.getSlaHours())
                    .reminderBeforeHours(stage.getReminderBeforeHours())
                    .escalationTarget(stage.getEscalationTarget())
                    .escalationUserId(stage.getEscalationUserId())
                    .allowInfoRequests(stage.isAllowInfoRequests())
                    .createdBy(userId)
                    .build());
            for (WorkflowStageParticipant p : participants.getOrDefault(stage.getStageId(), List.of())) {
                participantRepository.save(WorkflowStageParticipant.builder()
                        .tenantId(tenantId)
                        .stageId(stageCopy.getStageId())
                        .assigneeType(p.getAssigneeType())
                        .assigneeId(p.getAssigneeId())
                        .displayOrder(p.getDisplayOrder())
                        .createdBy(userId)
                        .build());
            }
        }
        participantRepository.flush();

        Map<String, Object> detail = snapshot(copy);
        detail.put("sourceTemplateId", source.getTemplateId());
        detail.put("sourceVersionNumber", source.getVersionNumber());
        audit(AuditActionCatalog.WORKFLOW_TEMPLATE_VERSION_CREATED, copy).newValue(detail).log();
        return toResponse(copy);
    }

    /**
     * Schema-publish pattern (DocumentTypeFieldServiceImpl#replaceMapping):
     * lock the parent document_types row so two activations for the same
     * type serialise, demote the current active version, then promote this
     * one. uq_workflow_templates_one_active_per_type is the backstop.
     */
    @Override
    @Transactional
    public WorkflowTemplateResponse activate(UUID templateId) {
        UUID tenantId = TenantContext.getTenantId();
        WorkflowTemplate template = templateRepository.lockByTemplateIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", templateId));
        if (template.getStatus() == TemplateStatus.active) {
            return toResponse(template);
        }
        DocumentType type = documentTypeRepository.lockByDocumentTypeIdAndTenantId(template.getDocumentTypeId(), tenantId)
                .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_DOCUMENT_TYPE_INVALID",
                        "The template's document type no longer exists"));
        if (type.getStatus() != DocumentType.DocumentTypeStatus.active) {
            throw new WorkflowValidationException("WORKFLOW_DOCUMENT_TYPE_INVALID",
                    "Document type " + type.getTypeName() + " is inactive");
        }
        validateForActivation(template);

        templateRepository.findFirstByTenantIdAndDocumentTypeIdAndStatusAndDeletedFalse(tenantId,
                        template.getDocumentTypeId(), TemplateStatus.active)
                .filter(current -> !current.getTemplateId().equals(template.getTemplateId()))
                .ifPresent(current -> {
                    current.setStatus(TemplateStatus.inactive);
                    touch(current);
                    templateRepository.saveAndFlush(current);
                    Map<String, Object> d = snapshot(current);
                    d.put("supersededBy", template.getTemplateId());
                    audit(AuditActionCatalog.WORKFLOW_TEMPLATE_DEACTIVATED, current).newValue(d).log();
                });

        template.setStatus(TemplateStatus.active);
        touch(template);
        try {
            templateRepository.saveAndFlush(template);
        } catch (DataIntegrityViolationException e) {
            throw new WorkflowStateException("WORKFLOW_TEMPLATE_ACTIVE_CONFLICT",
                    "Another template was activated for this document type at the same time - retry");
        }
        audit(AuditActionCatalog.WORKFLOW_TEMPLATE_ACTIVATED, template).newValue(snapshot(template)).log();
        return toResponse(template);
    }

    /** Blocks new submissions only - in-flight instances keep running on this version. */
    @Override
    @Transactional
    public WorkflowTemplateResponse deactivate(UUID templateId) {
        WorkflowTemplate template = templateRepository.lockByTemplateIdAndTenantId(templateId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", templateId));
        if (template.getStatus() != TemplateStatus.active) {
            throw new WorkflowStateException("WORKFLOW_TEMPLATE_NOT_ACTIVE", "Only an active template can be deactivated");
        }
        template.setStatus(TemplateStatus.inactive);
        touch(template);
        templateRepository.save(template);
        audit(AuditActionCatalog.WORKFLOW_TEMPLATE_DEACTIVATED, template).newValue(snapshot(template)).log();
        return toResponse(template);
    }

    @Override
    @Transactional
    public void delete(UUID templateId) {
        WorkflowTemplate template = templateRepository.lockByTemplateIdAndTenantId(templateId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", templateId));
        if (template.getStatus() == TemplateStatus.active || instanceRepository.existsByTemplateId(templateId)) {
            throw new WorkflowStateException("WORKFLOW_TEMPLATE_IN_USE",
                    "An active template, or one any workflow has run on, cannot be deleted - deactivate it instead");
        }
        OffsetDateTime now = OffsetDateTime.now();
        UUID userId = TenantContext.getUserId();
        List<WorkflowStage> stages = liveStages(template);
        participantsByStage(stages).values().stream().flatMap(List::stream)
                .forEach(p -> softDelete(p, userId, now));
        stages.forEach(s -> {
            s.setDeleted(true);
            s.setDeletedAt(now);
            s.setUpdatedBy(userId);
            s.setUpdatedAt(now);
        });
        template.setDeleted(true);
        template.setDeletedAt(now);
        touch(template);
        templateRepository.save(template);
        audit(AuditActionCatalog.WORKFLOW_TEMPLATE_DELETED, template).previousValue(snapshot(template)).log();
    }

    // ----------------------------------------------------------------- stages

    @Override
    @Transactional
    public WorkflowTemplateResponse addStage(UUID templateId, UpsertWorkflowStageRequest request) {
        WorkflowTemplate template = lockDraft(templateId);
        validateStage(request, template.getTenantId());
        List<WorkflowStage> stages = liveStages(template);
        int order = stages.stream().mapToInt(WorkflowStage::getStageOrder).max().orElse(0) + 1;
        WorkflowStage stage = stageRepository.saveAndFlush(applyStage(WorkflowStage.builder()
                .tenantId(template.getTenantId())
                .templateId(template.getTemplateId())
                .stageOrder(order)
                .createdBy(TenantContext.getUserId())
                .build(), request));
        replaceParticipants(stage, request.participants());
        return stagesChanged(template, "stage_added", stage.getStageId());
    }

    @Override
    @Transactional
    public WorkflowTemplateResponse updateStage(UUID templateId, UUID stageId, UpsertWorkflowStageRequest request) {
        WorkflowTemplate template = lockDraft(templateId);
        validateStage(request, template.getTenantId());
        WorkflowStage stage = findStage(template, stageId);
        applyStage(stage, request);
        stage.setUpdatedBy(TenantContext.getUserId());
        stage.setUpdatedAt(OffsetDateTime.now());
        stageRepository.save(stage);
        replaceParticipants(stage, request.participants());
        return stagesChanged(template, "stage_updated", stageId);
    }

    @Override
    @Transactional
    public WorkflowTemplateResponse deleteStage(UUID templateId, UUID stageId) {
        WorkflowTemplate template = lockDraft(templateId);
        WorkflowStage stage = findStage(template, stageId);
        UUID userId = TenantContext.getUserId();
        OffsetDateTime now = OffsetDateTime.now();
        participantRepository.findByStageIdAndTenantIdAndDeletedFalseOrderByDisplayOrderAscCreatedAtAsc(stageId,
                template.getTenantId()).forEach(p -> softDelete(p, userId, now));
        stage.setDeleted(true);
        stage.setDeletedAt(now);
        stage.setUpdatedBy(userId);
        stage.setUpdatedAt(now);
        stageRepository.save(stage);
        // Compact to 1..n - uq_workflow_stages_template_order is deferred to commit.
        int order = 1;
        for (WorkflowStage s : liveStages(template)) {
            if (!s.getStageId().equals(stageId)) {
                renumber(s, order++, userId, now);
            }
        }
        stageRepository.flush();
        return stagesChanged(template, "stage_deleted", stageId);
    }

    @Override
    @Transactional
    public WorkflowTemplateResponse reorderStages(UUID templateId, ReorderStagesRequest request) {
        WorkflowTemplate template = lockDraft(templateId);
        List<WorkflowStage> stages = liveStages(template);
        Set<UUID> live = stages.stream().map(WorkflowStage::getStageId).collect(Collectors.toSet());
        if (request.stageIds().size() != live.size() || !live.equals(new HashSet<>(request.stageIds()))) {
            throw new WorkflowValidationException("WORKFLOW_INVALID_STAGE_ORDER",
                    "stageIds must list every stage of the template exactly once");
        }
        Map<UUID, WorkflowStage> byId = stages.stream().collect(Collectors.toMap(WorkflowStage::getStageId, s -> s));
        UUID userId = TenantContext.getUserId();
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < request.stageIds().size(); i++) {
            renumber(byId.get(request.stageIds().get(i)), i + 1, userId, now);
        }
        stageRepository.flush();
        return stagesChanged(template, "stages_reordered", null);
    }

    // ---------------------------------------------------------------- helpers

    private WorkflowTemplate findOwned(UUID templateId) {
        return templateRepository.findByTemplateIdAndTenantIdAndDeletedFalse(templateId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", templateId));
    }

    private WorkflowTemplate lockDraft(UUID templateId) {
        WorkflowTemplate template = templateRepository.lockByTemplateIdAndTenantId(templateId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", templateId));
        if (template.getStatus() != TemplateStatus.draft) {
            throw new WorkflowStateException("WORKFLOW_TEMPLATE_NOT_DRAFT",
                    "Only a draft template can be edited - create a new version to change it");
        }
        return template;
    }

    private WorkflowStage findStage(WorkflowTemplate template, UUID stageId) {
        return stageRepository.findByStageIdAndTemplateIdAndTenantIdAndDeletedFalse(stageId, template.getTemplateId(),
                        template.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowStage", stageId));
    }

    private List<WorkflowStage> liveStages(WorkflowTemplate template) {
        return stageRepository.findByTemplateIdAndTenantIdAndDeletedFalseOrderByStageOrderAsc(template.getTemplateId(),
                template.getTenantId());
    }

    private Map<UUID, List<WorkflowStageParticipant>> participantsByStage(List<WorkflowStage> stages) {
        if (stages.isEmpty()) {
            return Map.of();
        }
        return participantRepository.findByStageIdInAndDeletedFalseOrderByDisplayOrderAscCreatedAtAsc(
                        stages.stream().map(WorkflowStage::getStageId).toList()).stream()
                .collect(Collectors.groupingBy(WorkflowStageParticipant::getStageId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    private void requireActiveDocumentType(UUID documentTypeId, UUID tenantId) {
        DocumentType type = documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId)
                .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_DOCUMENT_TYPE_INVALID",
                        "The selected document type does not exist"));
        if (type.getStatus() != DocumentType.DocumentTypeStatus.active) {
            throw new WorkflowValidationException("WORKFLOW_DOCUMENT_TYPE_INVALID",
                    "Document type " + type.getTypeName() + " is inactive");
        }
    }

    private void validateStage(UpsertWorkflowStageRequest request, UUID tenantId) {
        if (request.reminderBeforeHours() != null
                && (request.slaHours() == null || request.reminderBeforeHours() >= request.slaHours())) {
            throw new WorkflowValidationException("WORKFLOW_INVALID_STAGE",
                    "reminderBeforeHours needs an SLA and must be less than slaHours");
        }
        EscalationTarget target = request.escalationTarget() == null ? EscalationTarget.none : request.escalationTarget();
        if (target == EscalationTarget.named_user) {
            if (request.escalationUserId() == null) {
                throw new WorkflowValidationException("WORKFLOW_INVALID_STAGE",
                        "escalationUserId is required when escalationTarget is named_user");
            }
            requireAssignee(AssigneeType.user, request.escalationUserId(), tenantId);
        }
        Set<String> seen = new HashSet<>();
        for (ParticipantRequest p : request.participants()) {
            if (!seen.add(p.assigneeType() + ":" + p.assigneeId())) {
                throw new WorkflowValidationException("WORKFLOW_DUPLICATE_PARTICIPANT",
                        "The same " + p.assigneeType() + " is listed twice in this stage");
            }
            requireAssignee(p.assigneeType(), p.assigneeId(), tenantId);
        }
    }

    /** The assignee exists in the caller's tenant and is active - another tenant's id is "not found" (422). */
    private void requireAssignee(AssigneeType type, UUID id, UUID tenantId) {
        boolean ok = switch (type) {
            case user -> userRepository.findByUserIdAndTenantIdAndDeletedFalse(id, tenantId)
                    .map(u -> u.getStatus() == User.UserStatus.active).orElse(false);
            case role -> roleRepository.findByRoleIdAndTenantIdAndDeletedFalse(id, tenantId)
                    .map(r -> r.getStatus() == Role.RoleStatus.active).orElse(false);
            case department -> departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(id, tenantId)
                    .map(d -> d.getStatus() == Department.DepartmentStatus.active).orElse(false);
        };
        if (!ok) {
            throw new WorkflowValidationException("WORKFLOW_INVALID_ASSIGNEE",
                    "The selected " + type + " does not exist or is no longer active");
        }
    }

    private void validateForActivation(WorkflowTemplate template) {
        List<WorkflowStage> stages = liveStages(template);
        if (stages.isEmpty()) {
            throw new WorkflowValidationException("WORKFLOW_TEMPLATE_INVALID", "A template needs at least one stage");
        }
        Map<UUID, List<WorkflowStageParticipant>> participants = participantsByStage(stages);
        for (WorkflowStage stage : stages) {
            List<WorkflowStageParticipant> list = participants.getOrDefault(stage.getStageId(), List.of());
            if (list.isEmpty()) {
                throw new WorkflowValidationException("WORKFLOW_TEMPLATE_INVALID",
                        "Stage \"" + stage.getName() + "\" has no approvers");
            }
            for (WorkflowStageParticipant p : list) {
                requireAssignee(p.getAssigneeType(), p.getAssigneeId(), template.getTenantId());
            }
        }
    }

    private static WorkflowStage applyStage(WorkflowStage stage, UpsertWorkflowStageRequest request) {
        EscalationTarget target = request.escalationTarget() == null ? EscalationTarget.none : request.escalationTarget();
        stage.setName(request.name().trim());
        stage.setCompletionRule(request.completionRule());
        stage.setRejectionRule(request.rejectionRule());
        stage.setSlaHours(request.slaHours());
        stage.setReminderBeforeHours(request.reminderBeforeHours());
        stage.setEscalationTarget(target);
        stage.setEscalationUserId(target == EscalationTarget.named_user ? request.escalationUserId() : null);
        stage.setAllowInfoRequests(request.allowInfoRequests() == null || request.allowInfoRequests());
        return stage;
    }

    /**
     * Diff, not delete-and-reinsert: an unchanged entry keeps its
     * participant_id, and removed entries are soft-deleted (and flushed)
     * before new ones are inserted, so uq_workflow_stage_participants_stage_assignee
     * never sees a transient duplicate.
     */
    private void replaceParticipants(WorkflowStage stage, List<ParticipantRequest> requested) {
        UUID userId = TenantContext.getUserId();
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, WorkflowStageParticipant> existing = new HashMap<>();
        participantRepository.findByStageIdAndTenantIdAndDeletedFalseOrderByDisplayOrderAscCreatedAtAsc(
                        stage.getStageId(), stage.getTenantId())
                .forEach(p -> existing.put(p.getAssigneeType() + ":" + p.getAssigneeId(), p));

        Set<String> keep = new HashSet<>();
        for (ParticipantRequest r : requested) {
            keep.add(r.assigneeType() + ":" + r.assigneeId());
        }
        existing.forEach((key, p) -> {
            if (!keep.contains(key)) {
                softDelete(p, userId, now);
            }
        });
        participantRepository.flush();

        for (int i = 0; i < requested.size(); i++) {
            ParticipantRequest r = requested.get(i);
            int order = r.displayOrder() != null ? r.displayOrder() : i;
            WorkflowStageParticipant current = existing.get(r.assigneeType() + ":" + r.assigneeId());
            if (current != null) {
                if (current.getDisplayOrder() != order) {
                    current.setDisplayOrder(order);
                    current.setUpdatedBy(userId);
                    current.setUpdatedAt(now);
                    participantRepository.save(current);
                }
            } else {
                participantRepository.save(WorkflowStageParticipant.builder()
                        .tenantId(stage.getTenantId())
                        .stageId(stage.getStageId())
                        .assigneeType(r.assigneeType())
                        .assigneeId(r.assigneeId())
                        .displayOrder(order)
                        .createdBy(userId)
                        .build());
            }
        }
        participantRepository.flush();
    }

    private void softDelete(WorkflowStageParticipant p, UUID userId, OffsetDateTime now) {
        p.setDeleted(true);
        p.setDeletedAt(now);
        p.setUpdatedBy(userId);
        p.setUpdatedAt(now);
        participantRepository.save(p);
    }

    private void renumber(WorkflowStage stage, int order, UUID userId, OffsetDateTime now) {
        if (stage.getStageOrder() != order) {
            stage.setStageOrder(order);
            stage.setUpdatedBy(userId);
            stage.setUpdatedAt(now);
            stageRepository.save(stage);
        }
    }

    private WorkflowTemplateResponse stagesChanged(WorkflowTemplate template, String change, UUID stageId) {
        touch(template);
        templateRepository.save(template);
        WorkflowTemplateResponse response = toResponse(template);
        Map<String, Object> detail = snapshot(template);
        detail.put("change", change);
        detail.put("stageId", stageId);
        detail.put("stages", response.stages());
        audit(AuditActionCatalog.WORKFLOW_TEMPLATE_STAGES_UPDATED, template).newValue(detail).log();
        return response;
    }

    private static void touch(WorkflowTemplate template) {
        template.setUpdatedBy(TenantContext.getUserId());
        template.setUpdatedAt(OffsetDateTime.now());
    }

    private AuditEventBuilder audit(String action, WorkflowTemplate template) {
        return auditService.event(action)
                .objectType(AuditObjectTypes.WORKFLOW_TEMPLATE)
                .objectId(template.getTemplateId())
                .resourceName(template.getCode() + " v" + template.getVersionNumber())
                .objectVersion(template.getVersionNumber())
                .outcome(AuditOutcome.SUCCESS);
    }

    private static Map<String, Object> snapshot(WorkflowTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", t.getTemplateId());
        m.put("code", t.getCode());
        m.put("name", t.getName());
        m.put("versionNumber", t.getVersionNumber());
        m.put("status", t.getStatus().name());
        m.put("documentTypeId", t.getDocumentTypeId());
        m.put("onDocumentChange", t.getOnDocumentChange().name());
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Map<UUID, String> documentTypeNames(Collection<UUID> ids, UUID tenantId) {
        Map<UUID, String> names = new HashMap<>();
        new HashSet<>(ids).forEach(id -> documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(id, tenantId)
                .ifPresent(t -> names.put(id, t.getTypeName())));
        return names;
    }

    private WorkflowTemplateSummaryResponse toSummary(WorkflowTemplate t, String typeName) {
        return new WorkflowTemplateSummaryResponse(t.getTemplateId(), t.getCode(), t.getName(), t.getVersionNumber(),
                t.getStatus().name(), t.getDocumentTypeId(), typeName,
                stageRepository.countByTemplateIdAndDeletedFalse(t.getTemplateId()), t.getOnDocumentChange().name(),
                t.getCreatedAt(), t.getUpdatedAt());
    }

    private WorkflowTemplateResponse toResponse(WorkflowTemplate t) {
        UUID tenantId = t.getTenantId();
        List<WorkflowStage> stages = liveStages(t);
        Map<UUID, List<WorkflowStageParticipant>> participants = participantsByStage(stages);
        Map<String, String> names = assigneeNames(participants.values().stream().flatMap(List::stream).toList(), tenantId);
        List<WorkflowStageResponse> stageResponses = stages.stream()
                .map(s -> new WorkflowStageResponse(s.getStageId(), s.getStageOrder(), s.getName(),
                        s.getCompletionRule().name(), s.getRejectionRule().name(), s.getSlaHours(),
                        s.getReminderBeforeHours(), s.getEscalationTarget().name(), s.getEscalationUserId(),
                        s.isAllowInfoRequests(),
                        participants.getOrDefault(s.getStageId(), List.of()).stream()
                                .map(p -> new ParticipantResponse(p.getParticipantId(), p.getAssigneeType().name(),
                                        p.getAssigneeId(), names.get(p.getAssigneeType() + ":" + p.getAssigneeId()),
                                        p.getDisplayOrder()))
                                .toList()))
                .toList();
        String typeName = documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(t.getDocumentTypeId(), tenantId)
                .map(DocumentType::getTypeName).orElse(null);
        return new WorkflowTemplateResponse(t.getTemplateId(), t.getCode(), t.getName(), t.getDescription(),
                t.getVersionNumber(), t.getStatus().name(), t.getDocumentTypeId(), typeName,
                t.getOnDocumentChange().name(),
                t.getTemplateId() != null && instanceRepository.existsByTemplateId(t.getTemplateId()),
                stageResponses, t.getCreatedAt(), t.getUpdatedAt());
    }

    /** "type:id" -> user display name / role name / department name, tenant-scoped. */
    private Map<String, String> assigneeNames(List<WorkflowStageParticipant> participants, UUID tenantId) {
        Map<String, String> names = new HashMap<>();
        Map<AssigneeType, Set<UUID>> ids = new HashMap<>();
        participants.forEach(p -> ids.computeIfAbsent(p.getAssigneeType(), k -> new HashSet<>()).add(p.getAssigneeId()));
        userRepository.findAllById(ids.getOrDefault(AssigneeType.user, Set.of())).stream()
                .filter(u -> tenantId.equals(u.getTenantId()))
                .forEach(u -> names.put("user:" + u.getUserId(), u.getDisplayName()));
        roleRepository.findAllById(ids.getOrDefault(AssigneeType.role, Set.of())).stream()
                .filter(r -> tenantId.equals(r.getTenantId()))
                .forEach(r -> names.put("role:" + r.getRoleId(), r.getRoleName()));
        departmentRepository.findByDepartmentIdInAndTenantIdAndDeletedFalse(
                        ids.getOrDefault(AssigneeType.department, Set.of()), tenantId)
                .forEach(d -> names.put("department:" + d.getDepartmentId(), d.getName()));
        return names;
    }
}
