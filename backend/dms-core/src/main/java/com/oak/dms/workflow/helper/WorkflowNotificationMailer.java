package com.oak.dms.workflow.helper;

import com.oak.dms.user.User;
import com.oak.dms.workflow.config.WorkflowProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Builds and sends the Phase 1 workflow mails (WA-FR-006, WA-FR-014) - plain
 * {@link SimpleMailMessage}s through {@link JavaMailSender}, like
 * ThreatNotificationMailer. Throws on a send failure; the caller
 * (WorkflowNotificationListener) only logs it.
 */
@Component
@RequiredArgsConstructor
public class WorkflowNotificationMailer {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx");

    private final JavaMailSender mailSender;
    private final WorkflowProperties properties;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    /** To each person who can act on a newly created task (the user, or every eligible pool member). */
    public void sendTaskAssigned(User to, String documentTitle, String stageName, String poolName,
                                 OffsetDateTime dueAt, UUID documentId) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(nameOf(to)).append(",\n\n")
                .append("\"").append(documentTitle).append("\" is waiting for your approval at stage \"")
                .append(stageName).append("\".\n");
        if (poolName != null) {
            body.append("It was sent to ").append(poolName)
                    .append(" - the first member to approve or reject decides for the group.\n");
        }
        if (dueAt != null) {
            body.append("Due: ").append(WHEN.format(dueAt)).append("\n");
        }
        body.append("\nOpen My Tasks:\n").append(frontendBaseUrl).append("/tasks\n")
                .append("\nThe document:\n").append(frontendBaseUrl).append("/documents/").append(documentId).append("\n");
        send(to, "Approval needed: " + documentTitle, body.toString());
    }

    /** To the initiator when the workflow ends (WA-FR-014) - with the reason when rejected or cancelled. */
    public void sendOutcome(User to, String documentTitle, String outcome, String reason, UUID instanceId) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(nameOf(to)).append(",\n\n")
                .append("The approval workflow for \"").append(documentTitle).append("\" was ").append(outcome)
                .append(".\n");
        if (reason != null && !reason.isBlank()) {
            body.append("\nReason: ").append(reason).append("\n");
        }
        body.append("\nDetails:\n").append(frontendBaseUrl).append("/workflows/instances/").append(instanceId).append("\n");
        send(to, "Workflow " + outcome + ": " + documentTitle, body.toString());
    }

    /** To admins and the initiator when a stage activated with an entry nobody can act on. */
    public void sendUnassignable(User to, String documentTitle, String stageName, UUID instanceId) {
        String body = "Hello " + nameOf(to) + ",\n\n"
                + "The approval workflow for \"" + documentTitle + "\" reached stage \"" + stageName
                + "\", but at least one of its approver groups has nobody eligible to act (no active member with "
                + "approval permission).\n\n"
                + "The workflow is waiting. Add an eligible member to the group, or reassign the task.\n\n"
                + frontendBaseUrl + "/workflows/instances/" + instanceId + "\n";
        send(to, "Workflow stuck: no eligible approver for " + documentTitle, body);
    }

    // ---------------------------------------------------------------- Phase 2

    /** Delegation/reassignment: to the new holder (isNewHolder) and to the previous one (decision 2). */
    public void sendTaskMoved(User to, boolean isNewHolder, String kind, String documentTitle, String stageName,
                              String fromName, String toName, String note) {
        boolean reassigned = "reassigned".equals(kind);
        StringBuilder body = new StringBuilder().append("Hello ").append(nameOf(to)).append(",\n\n");
        if (isNewHolder) {
            body.append(reassigned ? "An administrator assigned you" : fromName + " delegated to you")
                    .append(" the approval of \"").append(documentTitle).append("\" at stage \"").append(stageName)
                    .append("\".\n");
        } else {
            body.append("Your approval task for \"").append(documentTitle).append("\" at stage \"").append(stageName)
                    .append("\" was ").append(reassigned ? "reassigned" : "delegated").append(" to ").append(toName)
                    .append(". You no longer need to act on it.\n");
        }
        if (note != null && !note.isBlank()) {
            body.append("\n").append(reassigned ? "Reason: " : "Note: ").append(note).append("\n");
        }
        body.append("\nOpen My Tasks:\n").append(frontendBaseUrl).append("/tasks\n");
        send(to, (isNewHolder ? "Approval needed: " : "Task " + (reassigned ? "reassigned" : "delegated") + ": ")
                + documentTitle, body.toString());
    }

    /** The reminder lead time before a task's due time was reached (decision 5). */
    public void sendReminder(User to, String documentTitle, String stageName, OffsetDateTime dueAt) {
        String body = "Hello " + nameOf(to) + ",\n\n"
                + "A reminder: \"" + documentTitle + "\" is still waiting for your approval at stage \"" + stageName
                + "\".\nDue: " + WHEN.format(dueAt) + "\n\nOpen My Tasks:\n" + frontendBaseUrl + "/tasks\n";
        send(to, "Reminder: approval due for " + documentTitle, body);
    }

    /** Past due: to the escalation target (isTarget), with a copy to the people still holding the task. */
    public void sendEscalated(User to, boolean isTarget, String documentTitle, String stageName, String targetName) {
        String body = isTarget
                ? "Hello " + nameOf(to) + ",\n\nThe approval of \"" + documentTitle + "\" at stage \"" + stageName
                + "\" is past its due time and has been escalated to you. You can now approve or reject it; the "
                + "original approvers can still act too.\n\nOpen My Tasks:\n" + frontendBaseUrl + "/tasks\n"
                : "Hello " + nameOf(to) + ",\n\nYour approval task for \"" + documentTitle + "\" at stage \""
                + stageName + "\" is past its due time and has been escalated to " + targetName
                + ". You can still act on it.\n\nOpen My Tasks:\n" + frontendBaseUrl + "/tasks\n";
        send(to, "Escalated: " + documentTitle, body);
    }

    /** Past due, but the configured escalation target couldn't be used (A2) - to whoever can reassign. */
    public void sendEscalationFailed(User to, String documentTitle, String stageName, String why, UUID instanceId) {
        String body = "Hello " + nameOf(to) + ",\n\nThe approval of \"" + documentTitle + "\" at stage \""
                + stageName + "\" is past its due time, but it couldn't be escalated: " + why + ".\n\n"
                + "Reassign the task or contact its approvers.\n\n" + frontendBaseUrl + "/workflows/instances/"
                + instanceId + "\n";
        send(to, "Escalation failed: " + documentTitle, body);
    }

    /** To the person asked for information (also when a request is redirected to them). */
    public void sendInfoRequested(User to, String documentTitle, String requesterName, String message,
                                  OffsetDateTime dueAt) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(nameOf(to)).append(",\n\n")
                .append(requesterName).append(" needs information from you to continue approving \"")
                .append(documentTitle).append("\":\n\n").append(message).append("\n");
        if (dueAt != null) {
            body.append("\nPlease answer by ").append(WHEN.format(dueAt)).append(".\n");
        }
        body.append("\nAnswer it under \"Requests for me\":\n").append(frontendBaseUrl).append("/tasks?view=requests\n");
        send(to, "Information requested: " + documentTitle, body.toString());
    }

    /** To the requester when their request was answered or declined. */
    public void sendInfoAnswered(User to, String documentTitle, String responderName, boolean declined, String text,
                                 int documentCount, UUID instanceId) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(nameOf(to)).append(",\n\n")
                .append(responderName).append(declined ? " declined" : " answered")
                .append(" your information request for \"").append(documentTitle).append("\".\n");
        if (text != null && !text.isBlank()) {
            body.append("\n").append(declined ? "Reason: " : "").append(text).append("\n");
        }
        if (documentCount > 0) {
            body.append("\n").append(documentCount).append(" supporting document(s) attached.\n");
        }
        body.append("\n").append(frontendBaseUrl).append("/workflows/instances/").append(instanceId).append("\n");
        send(to, "Information " + (declined ? "declined" : "provided") + ": " + documentTitle, body.toString());
    }

    /** A request was withdrawn, redirected away, or cancelled because the workflow moved on. */
    public void sendInfoClosed(User to, String documentTitle, String reason, UUID instanceId) {
        String body = "Hello " + nameOf(to) + ",\n\nAn information request about \"" + documentTitle
                + "\" no longer needs an answer.\n\nReason: " + reason + "\n\n" + frontendBaseUrl
                + "/workflows/instances/" + instanceId + "\n";
        send(to, "Information request closed: " + documentTitle, body);
    }

    private void send(User to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getNotifications().getMailFrom());
        message.setTo(to.getEmail());
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
    }

    private static String nameOf(User user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank() ? user.getDisplayName() : user.getEmail();
    }
}
