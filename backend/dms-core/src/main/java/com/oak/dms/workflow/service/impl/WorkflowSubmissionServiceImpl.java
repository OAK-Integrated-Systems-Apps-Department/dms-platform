package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowStageParticipant;
import com.oak.dms.workflow.entity.WorkflowTemplate;
import com.oak.dms.workflow.enums.AssigneeType;
import com.oak.dms.workflow.enums.CompletionRule;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.RejectionRule;
import com.oak.dms.workflow.enums.StageInstanceStatus;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.exception.WorkflowAccessException;
import com.oak.dms.workflow.exception.WorkflowStateException;
import com.oak.dms.workflow.exception.WorkflowValidationException;
import com.oak.dms.workflow.helper.TemplateSelector;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.helper.WorkflowLocks;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageParticipantRepository;
import com.oak.dms.workflow.repository.WorkflowStageRepository;
import com.oak.dms.workflow.service.ApproverResolver;
import com.oak.dms.workflow.service.WorkflowSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * See {@link WorkflowSubmissionService}. The document row lock serialises
 * two submissions of the same document; uq_workflow_instances_one_in_progress
 * is the backstop.
 */
@Service
@RequiredArgsConstructor
public class WorkflowSubmissionServiceImpl implements WorkflowSubmissionService {

    private static final Set<String> SUBMITTABLE_DOCUMENT_STATUSES = Set.of("draft", "published");

    private final DocumentRepository documentRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageRepository stageRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowStageParticipantRepository participantRepository;
    private final ApproverResolver approverResolver;
    private final TemplateSelector templateSelector;
    private final WorkflowEngine engine;
    private final WorkflowActionRecorder actions;
    private final WorkflowLocks locks;
    private final AuthorizationService authorizationService;

    @Override
    @Transactional
    public UUID submit(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        locks.applyLockTimeout();

        Document document = documentRepository.lockByDocumentIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        if (!SUBMITTABLE_DOCUMENT_STATUSES.contains(document.getStatus())) {
            throw new WorkflowStateException("WORKFLOW_DOCUMENT_NOT_ELIGIBLE",
                    "A " + document.getStatus() + " document cannot be submitted for approval");
        }
        if (instanceRepository.findFirstByTenantIdAndDocumentIdAndStatus(tenantId, documentId,
                InstanceStatus.in_progress).isPresent()) {
            throw new WorkflowStateException("WORKFLOW_ALREADY_IN_PROGRESS",
                    "This document is already in an approval workflow");
        }
        WorkflowTemplate template = templateSelector.selectFor(document)
                .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_NO_ACTIVE_TEMPLATE",
                        "No active workflow template is configured for this document's type"));
        return start(document, template, userId).getInstanceId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void precheck(Document document, WorkflowTemplate template, UUID initiatorId) {
        precheckApprovers(document.getTenantId(), stagesOf(template, document.getTenantId()), initiatorId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkflowInstance start(Document document, WorkflowTemplate template, UUID initiatorId) {
        UUID tenantId = document.getTenantId();
        List<WorkflowStage> stages = stagesOf(template, tenantId);
        precheckApprovers(tenantId, stages, initiatorId);

        WorkflowInstance instance;
        try {
            instance = instanceRepository.saveAndFlush(WorkflowInstance.builder()
                    .tenantId(tenantId)
                    .documentId(document.getDocumentId())
                    .documentVersionId(document.getLatestVersionId())
                    .templateId(template.getTemplateId())
                    .templateVersionNumber(template.getVersionNumber())
                    .status(InstanceStatus.in_progress)
                    .initiatedBy(initiatorId)
                    .createdBy(initiatorId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new WorkflowStateException("WORKFLOW_ALREADY_IN_PROGRESS",
                    "This document is already in an approval workflow");
        }

        List<WorkflowStageInstance> stageInstances = new ArrayList<>();
        for (WorkflowStage stage : stages) {
            stageInstances.add(WorkflowStageInstance.builder()
                    .tenantId(tenantId)
                    .instanceId(instance.getInstanceId())
                    .stageId(stage.getStageId())
                    .stageOrder(stage.getStageOrder())
                    .status(StageInstanceStatus.not_started)
                    .createdBy(initiatorId)
                    .build());
        }
        stageInstances = stageInstanceRepository.saveAllAndFlush(stageInstances);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("documentVersionId", instance.getDocumentVersionId());
        detail.put("templateCode", template.getCode());
        detail.put("templateVersionNumber", template.getVersionNumber());
        detail.put("stageCount", stages.size());
        actions.record(instance, null, null, WorkflowActionType.submitted, initiatorId, null, detail);
        engine.audit(AuditActionCatalog.WORKFLOW_SUBMITTED, instance)
                .newValue(engine.instanceSummary(instance, detail))
                .log();

        engine.activateStage(instance, stageInstances.get(0), initiatorId);
        return instance;
    }

    @Override
    @Transactional
    public void cancel(UUID instanceId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        locks.applyLockTimeout();
        WorkflowInstance instance = instanceRepository.lockByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        if (!userId.equals(instance.getInitiatedBy()) && !authorizationService.isTenantAdmin()) {
            throw new WorkflowAccessException("WORKFLOW_CANCEL_FORBIDDEN",
                    "Only the person who submitted this workflow, or a tenant administrator, can cancel it");
        }
        if (instance.getStatus() != InstanceStatus.in_progress) {
            throw new WorkflowStateException("WORKFLOW_NOT_IN_PROGRESS", "This workflow has already finished");
        }
        engine.cancel(instance, reason.trim(), userId, WorkflowActionType.cancelled, Map.of("cause", "withdrawn"));
    }

    private List<WorkflowStage> stagesOf(WorkflowTemplate template, UUID tenantId) {
        List<WorkflowStage> stages = stageRepository
                .findByTemplateIdAndTenantIdAndDeletedFalseOrderByStageOrderAsc(template.getTemplateId(), tenantId);
        if (stages.isEmpty()) {
            throw new WorkflowValidationException("WORKFLOW_TEMPLATE_INVALID", "The active template has no stages");
        }
        return stages;
    }

    /**
     * Dry-runs approver resolution for every stage before anything is written
     * (plan §5): an entry with nobody eligible, or a user entry naming the
     * initiator, blocks the submission. Also refuses the simple unsatisfiable
     * case of R5 - two entries of a stage that needs every entry to respond
     * whose only eligible person is the same user, who may decide once.
     */
    private void precheckApprovers(UUID tenantId, List<WorkflowStage> stages, UUID initiatorId) {
        Map<UUID, List<WorkflowStageParticipant>> participants = participantRepository
                .findByStageIdInAndDeletedFalseOrderByDisplayOrderAscCreatedAtAsc(
                        stages.stream().map(WorkflowStage::getStageId).toList()).stream()
                .collect(Collectors.groupingBy(WorkflowStageParticipant::getStageId));
        List<String> empty = new ArrayList<>();
        for (WorkflowStage stage : stages) {
            List<WorkflowStageParticipant> entries = participants.getOrDefault(stage.getStageId(), List.of());
            if (entries.isEmpty()) {
                throw new WorkflowValidationException("WORKFLOW_TEMPLATE_INVALID",
                        "Stage \"" + stage.getName() + "\" has no approvers");
            }
            boolean everyEntryMustRespond = stage.getCompletionRule() == CompletionRule.all
                    || stage.getRejectionRule() == RejectionRule.wait_for_all;
            Set<UUID> soleApprovers = new HashSet<>();
            for (WorkflowStageParticipant p : entries) {
                if (p.getAssigneeType() == AssigneeType.user && p.getAssigneeId().equals(initiatorId)) {
                    throw new WorkflowValidationException("WORKFLOW_INITIATOR_IS_APPROVER",
                            "You are an approver of stage \"" + stage.getName()
                                    + "\" and cannot approve your own submission");
                }
                Set<UUID> resolved = approverResolver.resolve(tenantId, p, initiatorId);
                if (resolved.isEmpty()) {
                    empty.add("stage " + stage.getStageOrder() + " \"" + stage.getName() + "\" (" + p.getAssigneeType()
                            + " " + p.getAssigneeId() + ")");
                } else if (everyEntryMustRespond && resolved.size() == 1 && !soleApprovers.add(resolved.iterator().next())) {
                    throw new WorkflowValidationException("WORKFLOW_UNSATISFIABLE_STAGE",
                            "Stage \"" + stage.getName() + "\" needs two entries whose only eligible approver is the same"
                                    + " person, who can decide only once");
                }
            }
        }
        if (!empty.isEmpty()) {
            throw new WorkflowValidationException("WORKFLOW_NO_ELIGIBLE_APPROVERS",
                    "Nobody is eligible to approve: " + String.join(", ", empty));
        }
    }
}
