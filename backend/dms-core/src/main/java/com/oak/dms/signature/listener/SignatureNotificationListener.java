package com.oak.dms.signature.listener;

import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.signature.event.SignatureInvalidatedEvent;
import com.oak.dms.signature.event.SignatureVerificationFailedEvent;
import com.oak.dms.user.User;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer.Doc;
import com.oak.dms.workflow.helper.WorkflowRecipients;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 1.4 Digital Signature Integration's notifications, modelled on
 * {@code WorkflowNotificationListener} (same BEFORE_COMMIT + shared
 * {@code NotificationService} pattern via {@link WorkflowNotificationMailer},
 * same recipients helper, reused rather than duplicated) - written through
 * the notification service inside the triggering transaction, so the inbox
 * entry commits atomically with the invalidation/verification it is about.
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

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onInvalidated(SignatureInvalidatedEvent event) {
        instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(event.instanceId(), event.tenantId())
                .map(i -> recipients.activeUser(i.getInitiatedBy(), event.tenantId()))
                .ifPresent(initiator -> {
                    Doc doc = new Doc(event.documentId(), recipients.documentTitle(event.documentId()));
                    mailer.sendSignatureInvalidated(List.of(initiator), doc, event.reason());
                });
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onVerificationFailed(SignatureVerificationFailedEvent event) {
        Doc doc = new Doc(event.documentId(), recipients.documentTitle(event.documentId()));
        List<User> admins = List.copyOf(
                recipients.activeUsers(userPermissions.tenantAdminIds(event.tenantId()), event.tenantId()).values());
        mailer.sendSignatureVerificationFailed(admins, doc, event.verificationResult());
    }
}
