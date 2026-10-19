package com.oak.dms.signature.listener;

import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.signature.event.SignatureInvalidatedEvent;
import com.oak.dms.signature.event.SignatureVerificationFailedEvent;
import com.oak.dms.user.User;
import com.oak.dms.workflow.helper.NotificationDispatcher;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer;
import com.oak.dms.workflow.helper.WorkflowRecipients;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 1.4 Digital Signature Integration's mail, modelled on
 * {@code WorkflowNotificationListener} (same AFTER_COMMIT + dispatcher
 * pattern, same recipients/mailer helpers, reused rather than duplicated).
 * <p>
 * {@link #onInvalidated} tells the workflow's initiator a signature no
 * longer stands behind their document. Deliberately scoped to the
 * initiator only, not also "the next signers" the feature's own design
 * discussion mentioned - {@link SignatureInvalidatedEvent} only knows the
 * now-cancelled/rejected instance, not any instance that might later
 * restart and need new signers; see the final report for this known scope
 * reduction. {@link #onVerificationFailed} alerts every tenant admin, since
 * an altered document or a signature that no longer matches is a security
 * event nobody participating in the original workflow may ever see on
 * their own.
 */
@Component
@RequiredArgsConstructor
public class SignatureNotificationListener {

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowRecipients recipients;
    private final UserPermissions userPermissions;
    private final WorkflowNotificationMailer mailer;
    private final NotificationDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvalidated(SignatureInvalidatedEvent event) {
        dispatcher.dispatch("signature-invalidated", event.instanceId(), () -> notifyInvalidated(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationFailed(SignatureVerificationFailedEvent event) {
        dispatcher.dispatch("signature-verification-failed", event.signatureId(), () -> notifyVerificationFailed(event));
    }

    private void notifyInvalidated(SignatureInvalidatedEvent event) {
        instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(event.instanceId(), event.tenantId())
                .map(i -> recipients.activeUser(i.getInitiatedBy(), event.tenantId()))
                .ifPresent(initiator -> NotificationDispatcher.send(
                        () -> mailer.sendSignatureInvalidated(initiator, recipients.documentTitle(event.documentId()),
                                event.reason(), event.documentId()),
                        "signature-invalidated", initiator, event.instanceId()));
    }

    private void notifyVerificationFailed(SignatureVerificationFailedEvent event) {
        String title = recipients.documentTitle(event.documentId());
        for (User admin : recipients.activeUsers(userPermissions.tenantAdminIds(event.tenantId()), event.tenantId()).values()) {
            NotificationDispatcher.send(() -> mailer.sendSignatureVerificationFailed(admin, title,
                    event.verificationResult(), event.documentId()), "signature-verification-failed", admin,
                    event.signatureId());
        }
    }
}
