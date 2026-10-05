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
