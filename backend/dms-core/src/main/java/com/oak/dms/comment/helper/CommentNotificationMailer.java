package com.oak.dms.comment.helper;

import com.oak.dms.user.User;
import com.oak.dms.workflow.config.WorkflowProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mention and reply mails (Phase 2 S9), plain text like the workflow mails
 * and sent from the same dms.workflow.notifications.mail-from.
 */
@Component
@RequiredArgsConstructor
public class CommentNotificationMailer {

    private final JavaMailSender mailSender;
    private final WorkflowProperties properties;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    /**
     * Decision 10: the mentioned person always gets the comment's text; the
     * link goes to the document only when they could open it, otherwise to
     * their mentions page.
     */
    public void sendMentioned(User to, String authorName, String documentTitle, String text, boolean canOpenDocument,
                              UUID documentId) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(nameOf(to)).append(",\n\n")
                .append(authorName).append(" mentioned you in a comment on \"").append(documentTitle).append("\":\n\n")
                .append(text).append("\n\n");
        if (canOpenDocument) {
            body.append("Open the document:\n").append(frontendBaseUrl).append("/documents/").append(documentId).append("\n");
        } else {
            body.append("You don't have access to this document, but you can read the comment here:\n")
                    .append(frontendBaseUrl).append("/mentions\n");
        }
        send(to, authorName + " mentioned you: " + documentTitle, body.toString());
    }

    public void sendReply(User to, String authorName, String documentTitle, String text, UUID documentId) {
        String body = "Hello " + nameOf(to) + ",\n\n" + authorName + " replied to your comment on \"" + documentTitle
                + "\":\n\n" + text + "\n\nOpen the document:\n" + frontendBaseUrl + "/documents/" + documentId + "\n";
        send(to, authorName + " replied: " + documentTitle, body);
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
