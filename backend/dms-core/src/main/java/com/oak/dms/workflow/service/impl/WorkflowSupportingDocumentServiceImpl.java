package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.DocumentVersionService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.workflow.dto.SupportingDocumentInput;
import com.oak.dms.workflow.dto.SupportingDocumentView;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowSupportingDocument;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.SupportingDocumentSource;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.exception.WorkflowAccessException;
import com.oak.dms.workflow.exception.WorkflowStateException;
import com.oak.dms.workflow.exception.WorkflowValidationException;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.helper.WorkflowEvidenceMapper;
import com.oak.dms.workflow.helper.WorkflowLocks;
import com.oak.dms.workflow.repository.WorkflowInfoRequestRepository;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowSupportingDocumentRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.WorkflowQueryService;
import com.oak.dms.workflow.service.WorkflowSupportingDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowSupportingDocumentServiceImpl implements WorkflowSupportingDocumentService {

    private final WorkflowSupportingDocumentRepository supportingRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowInfoRequestRepository infoRequestRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentVersionService documentVersionService;
    private final DocumentAccessService documentAccessService;
    private final TaskRouting taskRouting;
    private final AuthorizationService authorizationService;
    private final WorkflowQueryService queryService;
    private final WorkflowActionRecorder actions;
    private final WorkflowEngine engine;
    private final WorkflowEvidenceMapper evidenceMapper;
    private final WorkflowLocks locks;

    @Override
    @Transactional
    public SupportingDocumentView add(UUID instanceId, SupportingDocumentInput input) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        locks.applyLockTimeout();
        // Locked first, so an attachment can never land on a workflow that is being cancelled.
        WorkflowInstance instance = instanceRepository.lockByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        if (instance.getStatus() != InstanceStatus.in_progress) {
            throw new WorkflowStateException("WORKFLOW_NOT_IN_PROGRESS", "This workflow has already finished");
        }
        if (!isParticipant(instance, userId)) {
            throw new WorkflowAccessException("WORKFLOW_NOT_PARTICIPANT",
                    "Only the submitter, an approver holding a task, or someone asked for information can attach documents");
        }
        WorkflowSupportingDocument added = attach(instance, null, List.of(input), userId).get(0);
        return views(tenantId, List.of(added)).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportingDocumentView> list(UUID instanceId) {
        UUID tenantId = TenantContext.getTenantId();
        queryService.requireVisible(instanceId);
        return views(tenantId, supportingRepository.findByInstanceIdAndTenantIdAndDeletedFalseOrderByAddedAtAsc(
                instanceId, tenantId));
    }

    @Override
    @Transactional
    public DownloadedDocument content(UUID instanceId, UUID supportingDocumentId) {
        UUID tenantId = TenantContext.getTenantId();
        // The run must be visible to the caller (404 otherwise), exactly as for the supporting-document list.
        queryService.requireVisible(instanceId);
        WorkflowSupportingDocument doc = supportingRepository
                .findBySupportingDocumentIdAndTenantIdAndDeletedFalse(supportingDocumentId, tenantId)
                .filter(d -> d.getInstanceId().equals(instanceId))
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowSupportingDocument", supportingDocumentId));
        if (!authorizationService.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
            // canOpen is docm.documents.view plus the caller's own access (decision 18) - no per-run grant.
            throw new WorkflowAccessException("WORKFLOW_DOCUMENT_ACCESS_REQUIRED",
                    "You need permission to view documents to open this evidence");
        }
        // The pinned version (decision 17), never the document's current one. DocumentVersionService checks
        // the caller's document access (require view) and serves one specific version without dvc.versions.view.
        return documentVersionService.preview(doc.getDocumentId(), doc.getDocumentVersionId());
    }

    @Override
    @Transactional
    public void remove(UUID instanceId, UUID supportingDocumentId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        locks.applyLockTimeout();
        WorkflowInstance instance = instanceRepository.lockByInstanceIdAndTenantId(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        WorkflowSupportingDocument doc = supportingRepository
                .findBySupportingDocumentIdAndTenantIdAndDeletedFalse(supportingDocumentId, tenantId)
                .filter(d -> d.getInstanceId().equals(instanceId))
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowSupportingDocument", supportingDocumentId));
        if (!userId.equals(doc.getAddedBy())) {
            throw new WorkflowAccessException("WORKFLOW_NOT_PARTICIPANT", "Only the person who attached it can remove it");
        }
        if (instance.getStatus() != InstanceStatus.in_progress) {
            throw new WorkflowStateException("WORKFLOW_NOT_IN_PROGRESS", "This workflow has already finished");
        }
        OffsetDateTime now = OffsetDateTime.now();
        doc.setDeleted(true);
        doc.setDeletedAt(now);
        doc.setUpdatedBy(userId);
        doc.setUpdatedAt(now);
        supportingRepository.saveAndFlush(doc);
        Map<String, Object> detail = detail(doc, null);
        actions.record(instance, null, null, doc.getInfoRequestId(), WorkflowActionType.supporting_document_removed,
                userId, null, detail);
        audit(AuditActionCatalog.WORKFLOW_SUPPORTING_DOCUMENT_REMOVED, instance, doc, detail);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<WorkflowSupportingDocument> attach(WorkflowInstance instance, UUID infoRequestId,
                                                   List<SupportingDocumentInput> inputs, UUID actorUserId) {
        if (!authorizationService.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
            // Decision 18: access is never bypassed - you can only attach what you can open
            // yourself. The permission first, then the document's own access below.
            throw new WorkflowAccessException("WORKFLOW_DOCUMENT_ACCESS_REQUIRED",
                    "You need permission to view documents to attach one");
        }
        UUID tenantId = instance.getTenantId();
        // The actor is always the caller (standalone attach, or answering an information request).
        DocumentAccessView access = documentAccessService.forCaller();
        List<WorkflowSupportingDocument> saved = new ArrayList<>();
        for (SupportingDocumentInput input : inputs) {
            Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(input.documentId(), tenantId)
                    .filter(access::canSee)
                    .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_SUPPORTING_DOCUMENT_INVALID",
                            "The selected document does not exist"));
            if (document.getDocumentId().equals(instance.getDocumentId())) {
                throw new WorkflowValidationException("WORKFLOW_SUPPORTING_IS_SUBJECT",
                        "The document under approval can't be its own supporting document");
            }
            if (input.source() == SupportingDocumentSource.uploaded_new
                    && !actorUserId.equals(document.getCreatedBy()) && !actorUserId.equals(document.getOwnerUserId())) {
                throw new WorkflowValidationException("WORKFLOW_SUPPORTING_DOCUMENT_INVALID",
                        "Only a document you uploaded yourself can be attached as newly uploaded");
            }
            UUID versionId = input.documentVersionId() != null ? input.documentVersionId() : document.getLatestVersionId();
            DocumentVersion version = versionRepository
                    .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, document.getDocumentId(), tenantId)
                    .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_SUPPORTING_DOCUMENT_INVALID",
                            "That version doesn't belong to the selected document"));
            if (!version.isServable()) {
                throw new WorkflowValidationException("WORKFLOW_SUPPORTING_DOCUMENT_INVALID",
                        "That version hasn't passed its virus scan yet");
            }
            if (supportingRepository.existsByInstanceIdAndDocumentVersionIdAndDeletedFalse(instance.getInstanceId(),
                    version.getVersionId())) {
                throw new WorkflowStateException("WORKFLOW_SUPPORTING_DOCUMENT_EXISTS",
                        "\"" + document.getTitle() + "\" (this version) is already attached to this workflow");
            }
            String note = input.note() == null || input.note().isBlank() ? null : input.note().trim();
            WorkflowSupportingDocument doc;
            try {
                doc = supportingRepository.saveAndFlush(WorkflowSupportingDocument.builder()
                        .tenantId(tenantId)
                        .instanceId(instance.getInstanceId())
                        .infoRequestId(infoRequestId)
                        .documentId(document.getDocumentId())
                        .documentVersionId(version.getVersionId())
                        .source(input.source())
                        .addedBy(actorUserId)
                        .note(note)
                        .createdBy(actorUserId)
                        .build());
            } catch (DataIntegrityViolationException e) {
                // uq_workflow_supporting_documents_instance_version
                throw new WorkflowStateException("WORKFLOW_SUPPORTING_DOCUMENT_EXISTS",
                        "\"" + document.getTitle() + "\" (this version) is already attached to this workflow");
            }
            Map<String, Object> detail = detail(doc, document);
            if (infoRequestId == null) {
                actions.record(instance, null, null, null, WorkflowActionType.supporting_document_added, actorUserId,
                        note, detail);
            }
            audit(AuditActionCatalog.WORKFLOW_SUPPORTING_DOCUMENT_ADDED, instance, doc, detail);
            saved.add(doc);
        }
        return saved;
    }

    @Override
    public List<SupportingDocumentView> views(UUID tenantId, List<WorkflowSupportingDocument> documents) {
        return evidenceMapper.supportingViews(tenantId, documents);
    }

    /** The initiator, anyone who can act on an open task, or someone asked for information (§9). */
    private boolean isParticipant(WorkflowInstance instance, UUID userId) {
        if (userId.equals(instance.getInitiatedBy())
                || infoRequestRepository.existsByInstanceIdAndRequestedFromAndDeletedFalse(instance.getInstanceId(), userId)) {
            return true;
        }
        if (instance.getCurrentStageInstanceId() == null) {
            return false;
        }
        WorkflowStageInstance stage = stageInstanceRepository
                .findByStageInstanceIdAndTenantId(instance.getCurrentStageInstanceId(), instance.getTenantId())
                .orElse(null);
        if (stage == null) {
            return false;
        }
        for (WorkflowTask t : taskRepository.findByStageInstanceIdAndDeletedFalseOrderByCreatedAtAsc(stage.getStageInstanceId())) {
            if (TaskStatus.OPEN.contains(t.getStatus()) && taskRouting.canAct(instance.getTenantId(), t, userId)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> detail(WorkflowSupportingDocument doc, Document document) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supportingDocumentId", doc.getSupportingDocumentId().toString());
        m.put("documentId", doc.getDocumentId().toString());
        m.put("documentVersionId", doc.getDocumentVersionId().toString());
        m.put("source", doc.getSource().name());
        if (document != null) {
            m.put("title", document.getTitle());
        }
        if (doc.getInfoRequestId() != null) {
            m.put("infoRequestId", doc.getInfoRequestId().toString());
        }
        return m;
    }

    private void audit(String action, WorkflowInstance instance, WorkflowSupportingDocument doc, Map<String, Object> detail) {
        engine.audit(action, instance)
                .objectType(AuditObjectTypes.WORKFLOW_SUPPORTING_DOCUMENT)
                .objectId(doc.getSupportingDocumentId())
                .newValue(engine.instanceSummary(instance, detail))
                .log();
    }
}
