package com.oak.dms.signature.listener;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.enums.SignatureStatus;
import com.oak.dms.signature.event.SignatureInvalidatedEvent;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import com.oak.dms.signature.service.DocumentSignatureLockService;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.event.WorkflowOutcomeEvent;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Digital-signature consequences of a workflow reaching a terminal outcome
 * (decision 6) - two separate listeners for the same {@link WorkflowOutcomeEvent},
 * on purpose, for two different reasons:
 * <ul>
 *   <li>{@link #onCancelledOrRejected} invalidates the instance's valid
 *       signatures IN the same transaction that just decided the outcome
 *       ({@code @EventListener}, {@code Propagation.MANDATORY} - the same
 *       pattern {@code DocumentVersionChangeListener} already uses). A
 *       cancel/reject and the signatures it invalidates must commit or roll
 *       back together - there's no "the workflow was rejected but its
 *       signature is still valid" state worth tolerating even for an
 *       instant.</li>
 *   <li>{@link #onApproved} locks the document AFTER commit, in its own
 *       fresh transaction. This one CANNOT run in the approval transaction:
 *       {@code act()} already holds the instance lock, and the engine's own
 *       lock order is documents before instances, never the reverse within
 *       one transaction (see {@code WorkflowEngine}'s class doc) - taking
 *       the document lock here too would risk a deadlock against a
 *       concurrent download/version-change that already holds it. See
 *       {@code SignatureLockReconcileJob} for what catches a lock that
 *       fails to apply here (e.g. this instance's JVM crashes between
 *       commit and this listener running).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowSignatureListener {

    private final WorkflowInstanceRepository instanceRepository;
    private final DocumentSignatureRepository signatureRepository;
    private final DocumentSignatureLockService lockService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCancelledOrRejected(WorkflowOutcomeEvent event) {
        WorkflowInstance instance = instanceRepository
                .findByInstanceIdAndTenantIdAndDeletedFalse(event.instanceId(), event.tenantId()).orElse(null);
        if (instance == null || (instance.getStatus() != InstanceStatus.cancelled
                && instance.getStatus() != InstanceStatus.rejected)) {
            return;
        }
        List<DocumentSignature> valid = signatureRepository.findByTenantIdAndWorkflowInstanceIdAndStatus(
                event.tenantId(), instance.getInstanceId(), SignatureStatus.valid);
        if (valid.isEmpty()) {
            return;
        }

        String reason = instance.getStatus() == InstanceStatus.cancelled
                ? (instance.isVersionChangedFlag()
                        ? "The workflow restarted on a new document version before this signature's stage could finish."
                        : "The workflow was cancelled.")
                : "The workflow was rejected.";
        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> invalidatedIds = new ArrayList<>();
        for (DocumentSignature signature : valid) {
            signature.setStatus(SignatureStatus.invalidated);
            signature.setStatusChangedAt(now);
            signature.setStatusReason(reason);
            invalidatedIds.add(signature.getSignatureId());
        }
        signatureRepository.saveAll(valid);

        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_INVALIDATED)
                .tenantId(event.tenantId())
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(instance.getDocumentId())
                .outcome(AuditOutcome.SUCCESS)
                .reason(reason)
                .newValue(Map.of("workflowInstanceId", instance.getInstanceId(),
                        "signatureIds", invalidatedIds.stream().map(UUID::toString).toList()))
                .log();
        log.info("Invalidated {} signature(s) for workflow instance {} ({})", invalidatedIds.size(),
                instance.getInstanceId(), instance.getStatus());
        eventPublisher.publishEvent(new SignatureInvalidatedEvent(event.tenantId(), instance.getInstanceId(),
                instance.getDocumentId(), invalidatedIds, reason));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(WorkflowOutcomeEvent event) {
        WorkflowInstance instance = instanceRepository
                .findByInstanceIdAndTenantIdAndDeletedFalse(event.instanceId(), event.tenantId()).orElse(null);
        if (instance == null || instance.getStatus() != InstanceStatus.approved) {
            return;
        }
        boolean hasValidSignature = !signatureRepository.findByTenantIdAndWorkflowInstanceIdAndStatus(
                event.tenantId(), instance.getInstanceId(), SignatureStatus.valid).isEmpty();
        if (!hasValidSignature) {
            return;
        }
        // lockService.lock re-checks for itself, in its own REQUIRES_NEW
        // transaction - this outer check is only to skip opening one when
        // there is obviously nothing to do.
        lockService.lock(event.tenantId(), instance.getDocumentId(), instance.getDocumentVersionId());
    }
}
