package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.event.DocumentVersionCreatedEvent;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowTemplate;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.OnDocumentChange;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.helper.TemplateSelector;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.helper.WorkflowLocks;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowTemplateRepository;
import com.oak.dms.workflow.service.WorkflowSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Decision 10: a document changed after submission. Runs synchronously
 * inside the promotion transaction (DocumentVersionPromoter#persist), so the
 * flag or restart commits atomically with the new version. Lock order:
 * document -> instance. Never reads TenantContext - promotion can run in the
 * retry scheduler or a cloud callback.
 * <ul>
 *   <li>{@code flag_only}: set version_changed_flag; the instance continues
 *   on its snapshotted version.</li>
 *   <li>{@code restart}: cancel the instance and submit the new version
 *   through the CURRENTLY active template (Q4), as the original initiator.
 *   If that re-submission is impossible (no active template, nobody
 *   eligible...), fall back to flag-only and record why.</li>
 * </ul>
 * An upload never fails because of the workflow: expected workflow refusals
 * (ApiException) are handled here; anything else still propagates, since it
 * shares the upload's transaction and must not leave a half-applied change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVersionChangeListener {

    private final DocumentRepository documentRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final TemplateSelector templateSelector;
    private final WorkflowSubmissionService submissionService;
    private final WorkflowEngine engine;
    private final WorkflowActionRecorder actions;
    private final WorkflowLocks locks;
    private final WorkflowNewVersionNotifier newVersionNotifier;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDocumentVersionCreated(DocumentVersionCreatedEvent event) {
        // Scalar lookup, so the instance is first loaded by its lock (a row read
        // earlier in the transaction would come back stale from FOR UPDATE).
        Optional<UUID> inFlight = instanceRepository.findInProgressInstanceId(event.tenantId(), event.documentId());
        if (inFlight.isEmpty()) {
            return;
        }
        locks.applyLockTimeout();
        Document document = documentRepository.lockByDocumentIdAndTenantId(event.documentId(), event.tenantId())
                .orElse(null);
        WorkflowInstance instance = instanceRepository.lockByInstanceIdAndTenantId(inFlight.get(), event.tenantId())
                .orElse(null);
        if (document == null || instance == null || instance.getStatus() != InstanceStatus.in_progress) {
            return;
        }
        WorkflowTemplate template = templateRepository.findById(instance.getTemplateId()).orElse(null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromVersionId", instance.getDocumentVersionId());
        detail.put("toVersionId", event.newVersionId());

        if (template != null && template.getOnDocumentChange() == OnDocumentChange.restart) {
            Optional<WorkflowTemplate> active = templateSelector.selectFor(document);
            if (active.isPresent()) {
                try {
                    restart(instance, document, active.get(), event, detail);
                    return;
                } catch (ApiException e) {
                    // restart() pre-checks before it cancels, so nothing was written.
                    log.info("Workflow {} could not restart on document {} version {} ({}); flagging instead",
                            instance.getInstanceId(), event.documentId(), event.newVersionId(), e.getMessage());
                    detail.put("restartRefused", e.getCode() != null ? e.getCode() : e.getMessage());
                }
            } else {
                detail.put("restartRefused", "WORKFLOW_NO_ACTIVE_TEMPLATE");
            }
        }
        flag(instance, event, detail);
    }

    private void flag(WorkflowInstance instance, DocumentVersionCreatedEvent event, Map<String, Object> detail) {
        instance.setVersionChangedFlag(true);
        instance.setUpdatedBy(event.uploadedBy());
        instance.setUpdatedAt(OffsetDateTime.now());
        instanceRepository.save(instance);
        actions.record(instance, null, null, WorkflowActionType.version_changed, event.uploadedBy(), null, detail);
        engine.audit(AuditActionCatalog.WORKFLOW_DOCUMENT_VERSION_CHANGED, instance)
                .newValue(engine.instanceSummary(instance, detail))
                .log();
        // The approval carries on with the submitted version: tell the people it is waiting on (3.2, N10).
        newVersionNotifier.notifyHolders(instance, event);
    }

    /**
     * Pre-check, cancel, then start again. The dry-run pre-check comes first
     * because a refusal after the cancel could not be undone without failing
     * the upload. The new instance is inserted only after the old one's
     * in_progress status is flushed away (engine.cancel), so
     * uq_workflow_instances_one_in_progress holds throughout.
     */
    private void restart(WorkflowInstance old, Document document, WorkflowTemplate active,
                         DocumentVersionCreatedEvent event, Map<String, Object> detail) {
        submissionService.precheck(document, active, old.getInitiatedBy());
        Map<String, Object> cancelDetail = new LinkedHashMap<>(detail);
        cancelDetail.put("cause", "document_version_changed");
        engine.cancel(old, "Restarted: the document changed to a new version during approval", event.uploadedBy(),
                WorkflowActionType.restarted, cancelDetail);
        WorkflowInstance fresh = submissionService.start(document, active, old.getInitiatedBy());
        Map<String, Object> restartDetail = new LinkedHashMap<>(detail);
        restartDetail.put("previousInstanceId", old.getInstanceId());
        restartDetail.put("newInstanceId", fresh.getInstanceId());
        engine.audit(AuditActionCatalog.WORKFLOW_RESTARTED, fresh)
                .newValue(engine.instanceSummary(fresh, restartDetail))
                .log();
    }
}
