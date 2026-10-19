package com.oak.dms.comment.helper;

import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Mention and reply notifications (Phase 2 S9), routed through the shared
 * {@link NotificationService} (3.2, N8) and access-aware (N9, decision 7): the
 * recipient who can open the document gets its title and a link to it. One who
 * can't still gets what they are entitled to - a mentioned person always reads
 * the comment itself (decision 10), through their mentions page - but never the
 * document's title or a link to it.
 */
@Component
@RequiredArgsConstructor
public class CommentNotificationMailer {

    private static final String MODULE = "documents";

    private final NotificationService notificationService;

    public void sendMentioned(User to, String authorName, UUID documentId, String documentTitle, String text) {
        notificationService.notify(NotificationRequest.builder()
                .tenantId(to.getTenantId())
                .recipientUserIds(List.of(to.getUserId()))
                .categoryCode("comment.mention")
                .title(authorName + " mentioned you: " + documentTitle)
                .body(authorName + " mentioned you in a comment on \"" + documentTitle + "\":\n\n" + text)
                .linkUrl("/documents/" + documentId)
                .documentId(documentId)
                .requireViewPermission(true)
                .neutralTitle(authorName + " mentioned you in a comment")
                .neutralBody(authorName + " mentioned you in a comment:\n\n" + text
                        + "\n\nYou don't have access to the document, but you can read the comment here.")
                .neutralLinkUrl("/mentions")
                .objectType("document")
                .objectId(documentId)
                .sourceModule(MODULE)
                .build());
    }

    public void sendReply(User to, String authorName, UUID documentId, String documentTitle, String text) {
        notificationService.notify(NotificationRequest.builder()
                .tenantId(to.getTenantId())
                .recipientUserIds(List.of(to.getUserId()))
                .categoryCode("comment.reply")
                .title(authorName + " replied: " + documentTitle)
                .body(authorName + " replied to your comment on \"" + documentTitle + "\":\n\n" + text)
                .linkUrl("/documents/" + documentId)
                .documentId(documentId)
                .requireViewPermission(true)
                .neutralTitle(authorName + " replied to your comment")
                .neutralBody(authorName + " replied to your comment:\n\n" + text)
                .neutralLinkUrl(null)
                .objectType("document")
                .objectId(documentId)
                .sourceModule(MODULE)
                .build());
    }
}
