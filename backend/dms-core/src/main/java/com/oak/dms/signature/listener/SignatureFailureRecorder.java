package com.oak.dms.signature.listener;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.signature.event.SignatureRejectedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * A refused signing attempt rolls back the approval transaction it ran
 * inside - and with it, any audit row {@code DocumentSignatureService#sign}
 * wrote directly in that same transaction. {@link SignatureRejectedEvent} is
 * published right before every such throw specifically so this listener,
 * which runs AFTER the rollback in a brand new {@code REQUIRES_NEW}
 * transaction, can still get the failed (or suspicious - wrong password, a
 * tampered file) attempt into the audit trail. Explicitly sets
 * {@link com.oak.dms.audit.helper.AuditEventBuilder#tenantId}/{@code actor}
 * from the event rather than relying on {@code TenantContext} still being
 * bound by the time this fires.
 */
@Component
@RequiredArgsConstructor
public class SignatureFailureRecorder {

    private final AuditService auditService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSignatureRejected(SignatureRejectedEvent event) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("code", event.code());
        detail.put("taskId", event.taskId());
        detail.put("occurredAt", event.occurredAt());

        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_REJECTED)
                .tenantId(event.tenantId())
                .actor(event.actorUserId(), null)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(event.documentId())
                .outcome(AuditOutcome.FAILURE)
                .failureReason(event.message())
                .newValue(detail)
                .log();
    }
}
