package com.oak.dms.workflow.helper;

import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;

/**
 * Builds the workflow notifications (WA-FR-006/014, Phase 2 activity) and
 * routes them through the shared {@link NotificationService} (3.2, N8). The
 * subject (title), key facts (body) and link are preserved from the old
 * plain-text mails; delivery (in-app + email) and retries are handled by the
 * service and its dispatcher.
 * <p>
 * Every method takes the recipients of ONE event, all of whom get the same
 * content, so the service makes a single access check for them (one Keycloak
 * lookup, not one per recipient). Every notification names its document and is
 * therefore access-aware (N9, decision 7): a recipient who can't open the
 * document gets the same notification with "a document" in place of its title
 * and no document link.
 * <p>
 * No dedupe key: each event fires once per transaction, and a repeat (a
 * resubmission, a second delegation to the same person) is a genuinely new
 * notification - exactly as the old mails behaved.
 * <p>
 * {@link #sendSignatureInvalidated}/{@link #sendSignatureVerificationFailed}
 * are 1.4 Digital Signature Integration's own notifications, reusing this
 * class and the same {@link #notifyAbout} plumbing rather than a new one -
 * called from {@code com.oak.dms.signature.listener.SignatureNotificationListener}.
 */
@Component
@RequiredArgsConstructor
public class WorkflowNotificationMailer {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx");
    private static final String MODULE = "workflow";

    private final NotificationService notificationService;

    /** The document a notification is about: its id (for the access check) and its title. */
    public record Doc(UUID id, String title) {
    }

    /** How the document is named in a title / in a sentence; "a document" when the recipient can't open it. */
    private record Ref(String plain, String quoted) {
        static final Ref NEUTRAL = new Ref("a document", "a document");

        static Ref of(String title) {
            return new Ref(title, "\"" + title + "\"");
        }

        /** The reference at the start of a sentence. */
        String sentence() {
            return quoted.isEmpty() ? quoted : Character.toUpperCase(quoted.charAt(0)) + quoted.substring(1);
        }
    }

    public void sendTaskAssigned(Collection<User> to, Doc doc, String stageName, String poolName,
                                 OffsetDateTime dueAt) {
        notifyAbout(to, doc, "workflow.task.assigned",
                r -> "Approval needed: " + r.plain(),
                r -> {
                    StringBuilder body = new StringBuilder(r.sentence())
                            .append(" is waiting for your approval at stage \"").append(stageName).append("\".");
                    if (poolName != null) {
                        body.append("\nIt was sent to ").append(poolName)
                                .append(" - the first member to approve or reject decides for the group.");
                    }
                    if (dueAt != null) {
                        body.append("\nDue: ").append(WHEN.format(dueAt));
                    }
                    return body.toString();
                },
                "/documents/" + doc.id(), "/tasks", "document", doc.id());
    }

    public void sendOutcome(Collection<User> to, Doc doc, String outcome, String reason, UUID instanceId) {
        notifyAbout(to, doc, "workflow.outcome",
                r -> "Workflow " + outcome + ": " + r.plain(),
                r -> {
                    StringBuilder body = new StringBuilder("The approval workflow for ").append(r.quoted())
                            .append(" was ").append(outcome).append(".");
                    if (reason != null && !reason.isBlank()) {
                        body.append("\nReason: ").append(reason);
                    }
                    return body.toString();
                },
                instanceLink(instanceId), instanceLink(instanceId), "workflow_instance", instanceId);
    }

    public void sendUnassignable(Collection<User> to, Doc doc, String stageName, UUID instanceId) {
        notifyAbout(to, doc, "workflow.stage.unassignable",
                r -> "Workflow stuck: no eligible approver for " + r.plain(),
                r -> "The approval workflow for " + r.quoted() + " reached stage \"" + stageName
                        + "\", but at least one of its approver groups has nobody eligible to act (no active member "
                        + "with approval permission). The workflow is waiting. Add an eligible member to the group, "
                        + "or reassign the task.",
                instanceLink(instanceId), instanceLink(instanceId), "workflow_instance", instanceId);
    }

    public void sendTaskMoved(Collection<User> to, boolean isNewHolder, String kind, Doc doc, String stageName,
                              String fromName, String toName, String note) {
        boolean reassigned = "reassigned".equals(kind);
        String subject = isNewHolder ? "Approval needed: " : "Task " + (reassigned ? "reassigned" : "delegated") + ": ";
        notifyAbout(to, doc, "workflow.task.delegated",
                r -> subject + r.plain(),
                r -> {
                    StringBuilder body = new StringBuilder();
                    if (isNewHolder) {
                        body.append(reassigned ? "An administrator assigned you" : fromName + " delegated to you")
                                .append(" the approval of ").append(r.quoted()).append(" at stage \"")
                                .append(stageName).append("\".");
                    } else {
                        body.append("Your approval task for ").append(r.quoted()).append(" at stage \"")
                                .append(stageName).append("\" was ").append(reassigned ? "reassigned" : "delegated")
                                .append(" to ").append(toName).append(". You no longer need to act on it.");
                    }
                    if (note != null && !note.isBlank()) {
                        body.append("\n").append(reassigned ? "Reason: " : "Note: ").append(note);
                    }
                    return body.toString();
                },
                "/tasks", "/tasks", "document", null);
    }

    public void sendReminder(Collection<User> to, Doc doc, String stageName, OffsetDateTime dueAt) {
        notifyAbout(to, doc, "workflow.task.reminder",
                r -> "Reminder: approval due for " + r.plain(),
                r -> "A reminder: " + r.quoted() + " is still waiting for your approval at stage \"" + stageName
                        + "\"." + (dueAt != null ? "\nDue: " + WHEN.format(dueAt) : ""),
                "/tasks", "/tasks", "document", null);
    }

    public void sendEscalated(Collection<User> to, boolean isTarget, Doc doc, String stageName, String targetName) {
        notifyAbout(to, doc, "workflow.task.escalated",
                r -> "Escalated: " + r.plain(),
                r -> isTarget
                        ? "The approval of " + r.quoted() + " at stage \"" + stageName
                        + "\" is past its due time and has been escalated to you. You can now approve or reject it; "
                        + "the original approvers can still act too."
                        : "Your approval task for " + r.quoted() + " at stage \"" + stageName
                        + "\" is past its due time and has been escalated to " + targetName
                        + ". You can still act on it.",
                "/tasks", "/tasks", "document", null);
    }

    public void sendEscalationFailed(Collection<User> to, Doc doc, String stageName, String why, UUID instanceId) {
        notifyAbout(to, doc, "workflow.escalation.failed",
                r -> "Escalation failed: " + r.plain(),
                r -> "The approval of " + r.quoted() + " at stage \"" + stageName
                        + "\" is past its due time, but it couldn't be escalated: " + why
                        + ".\nReassign the task or contact its approvers.",
                instanceLink(instanceId), instanceLink(instanceId), "workflow_instance", instanceId);
    }

    public void sendInfoRequested(Collection<User> to, Doc doc, String requesterName, String message,
                                  OffsetDateTime dueAt) {
        notifyAbout(to, doc, "workflow.info.requested",
                r -> "Information requested: " + r.plain(),
                r -> requesterName + " needs information from you to continue approving " + r.quoted() + ":\n\n"
                        + message + (dueAt != null ? "\n\nPlease answer by " + WHEN.format(dueAt) + "." : ""),
                "/tasks?view=requests", "/tasks?view=requests", "document", null);
    }

    public void sendInfoAnswered(Collection<User> to, Doc doc, String responderName, boolean declined, String text,
                                 int documentCount, UUID instanceId) {
        notifyAbout(to, doc, "workflow.info.resolved",
                r -> "Information " + (declined ? "declined" : "provided") + ": " + r.plain(),
                r -> {
                    StringBuilder body = new StringBuilder(responderName).append(declined ? " declined" : " answered")
                            .append(" your information request for ").append(r.quoted()).append(".");
                    if (text != null && !text.isBlank()) {
                        body.append("\n").append(declined ? "Reason: " : "").append(text);
                    }
                    if (documentCount > 0) {
                        body.append("\n").append(documentCount).append(" supporting document(s) attached.");
                    }
                    return body.toString();
                },
                instanceLink(instanceId), instanceLink(instanceId), "workflow_instance", instanceId);
    }

    public void sendInfoClosed(Collection<User> to, Doc doc, String reason, UUID instanceId) {
        notifyAbout(to, doc, "workflow.info.resolved",
                r -> "Information request closed: " + r.plain(),
                r -> "An information request about " + r.quoted() + " no longer needs an answer.\nReason: " + reason,
                instanceLink(instanceId), instanceLink(instanceId), "workflow_instance", instanceId);
    }

    /** The status of a document the recipient owns changed - an approval ended (N10, optional tier). */
    public void sendStatusChanged(Collection<User> to, Doc doc, String outcome, UUID instanceId) {
        notifyAbout(to, doc, "document.status-changed",
                r -> "Document " + outcome + ": " + r.plain(),
                r -> "The approval workflow for " + r.quoted() + ", a document you own, was " + outcome + ".",
                instanceLink(instanceId), instanceLink(instanceId), "workflow_instance", instanceId);
    }

    /** A new version landed while the approval was running (N10); the instance continues on its snapshot. */
    public void sendNewVersion(Collection<User> to, Doc doc, String uploaderName, UUID instanceId) {
        notifyAbout(to, doc, "workflow.new-version",
                r -> "New version uploaded during approval: " + r.plain(),
                r -> uploaderName + " uploaded a new version of " + r.quoted() + " while it is awaiting your "
                        + "approval. The approval continues on the version that was submitted; the workflow is "
                        + "flagged so the new version can be reviewed.",
                "/documents/" + doc.id(), "/tasks", "document", doc.id());
    }

    private static String instanceLink(UUID instanceId) {
        return "/workflows/instances/" + instanceId;
    }

    private void notifyAbout(Collection<User> to, Doc doc, String category, Function<Ref, String> title,
                             Function<Ref, String> body, String link, String neutralLink, String objectType,
                             UUID objectId) {
        if (to.isEmpty()) {
            return;
        }
        Ref real = Ref.of(doc.title());
        notificationService.notify(NotificationRequest.builder()
                .tenantId(to.iterator().next().getTenantId())
                .recipientUserIds(to.stream().map(User::getUserId).toList())
                .categoryCode(category)
                .title(title.apply(real))
                .body(body.apply(real))
                .linkUrl(link)
                .documentId(doc.id())
                .neutralTitle(title.apply(Ref.NEUTRAL))
                .neutralBody(body.apply(Ref.NEUTRAL))
                .neutralLinkUrl(neutralLink)
                .objectType(objectType)
                .objectId(objectId)
                .sourceModule(MODULE)
                .build());
    }

    // ---------------------------------------------------------------- 1.4 Digital Signature Integration

    /** To the initiator when a workflow ends cancelled/rejected and invalidated a signature on their document. */
    public void sendSignatureInvalidated(Collection<User> to, Doc doc, String reason) {
        notifyAbout(to, doc, "document.signature-invalidated",
                r -> "Signature no longer valid: " + r.plain(),
                r -> "A signature on " + r.quoted() + " is no longer valid: " + reason
                        + "\nIf this document still needs to be approved, it will need to be signed again once its "
                        + "workflow is resubmitted.",
                "/documents/" + doc.id(), "/documents/" + doc.id(), "document", doc.id());
    }

    /** To tenant admins when an on-demand verification finds a signature that no longer checks out. */
    public void sendSignatureVerificationFailed(Collection<User> to, Doc doc, String verificationResult) {
        notifyAbout(to, doc, "document.signature-verification-failed",
                r -> "Signature verification failed: " + r.plain(),
                r -> "A signature verification on " + r.quoted() + " failed (" + verificationResult + ") - the "
                        + "document may have been altered after it was signed, or the signature itself no longer "
                        + "matches. It has been marked invalidated.",
                "/documents/" + doc.id(), "/documents/" + doc.id(), "document", doc.id());
    }
}
