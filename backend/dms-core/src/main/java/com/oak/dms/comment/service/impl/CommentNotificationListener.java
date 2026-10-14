package com.oak.dms.comment.service.impl;

import com.oak.dms.comment.entity.CommentMention;
import com.oak.dms.comment.entity.DocumentComment;
import com.oak.dms.comment.event.CommentCreatedEvent;
import com.oak.dms.comment.event.CommentMentionedEvent;
import com.oak.dms.comment.helper.CommentNotificationMailer;
import com.oak.dms.comment.helper.MentionParser;
import com.oak.dms.comment.repository.CommentMentionRepository;
import com.oak.dms.comment.repository.DocumentCommentRepository;
import com.oak.dms.user.User;
import com.oak.dms.workflow.helper.NotificationDispatcher;
import com.oak.dms.workflow.helper.WorkflowRecipients;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Mention and reply mails, after commit and off the request thread
 * ({@link NotificationDispatcher}). A mention mail sets notified_at once it
 * went out; a reply notifies the thread's root author unless they wrote the
 * reply or are mentioned in it (they get the mention mail instead).
 */
@Component
@RequiredArgsConstructor
public class CommentNotificationListener {

    private final DocumentCommentRepository commentRepository;
    private final CommentMentionRepository mentionRepository;
    private final WorkflowRecipients recipients;
    private final CommentNotificationMailer mailer;
    private final NotificationDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMentioned(CommentMentionedEvent event) {
        dispatcher.dispatch("mention", event.commentId(), () -> notifyMentioned(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(CommentCreatedEvent event) {
        dispatcher.dispatch("reply", event.commentId(), () -> notifyReply(event));
    }

    private void notifyMentioned(CommentMentionedEvent event) {
        DocumentComment comment = commentRepository.findById(event.commentId()).orElse(null);
        if (comment == null || comment.isDeleted()) {
            return;
        }
        String author = name(comment.getAuthorUserId(), event.tenantId());
        String title = recipients.documentTitle(comment.getDocumentId());
        String text = MentionParser.toPlainText(comment.getBody());
        for (CommentMention mention : mentionRepository.findAllById(event.mentionIds())) {
            User to = recipients.activeUser(mention.getMentionedUserId(), event.tenantId());
            if (to == null) {
                continue;
            }
            boolean sent = NotificationDispatcher.send(() -> mailer.sendMentioned(to, author, title, text,
                    mention.isHadDocumentAccess(), comment.getDocumentId()), "mention", to, comment.getCommentId());
            if (sent) {
                mentionRepository.markNotified(mention.getMentionId(), OffsetDateTime.now());
            }
        }
    }

    private void notifyReply(CommentCreatedEvent event) {
        DocumentComment reply = commentRepository.findById(event.commentId()).orElse(null);
        if (reply == null || reply.getParentCommentId() == null) {
            return;
        }
        DocumentComment root = commentRepository.findById(reply.getParentCommentId()).orElse(null);
        if (root == null || root.isDeleted() || root.getAuthorUserId().equals(reply.getAuthorUserId())
                || mentionRepository.existsByTenantIdAndCommentIdAndMentionedUserId(event.tenantId(),
                reply.getCommentId(), root.getAuthorUserId())) {
            return;
        }
        User to = recipients.activeUser(root.getAuthorUserId(), event.tenantId());
        if (to == null) {
            return;
        }
        String author = name(reply.getAuthorUserId(), event.tenantId());
        NotificationDispatcher.send(() -> mailer.sendReply(to, author, recipients.documentTitle(reply.getDocumentId()),
                MentionParser.toPlainText(reply.getBody()), reply.getDocumentId()), "reply", to, reply.getCommentId());
    }

    private String name(UUID userId, UUID tenantId) {
        User u = recipients.activeUsers(List.of(userId), tenantId).get(userId);
        return u != null && u.getDisplayName() != null ? u.getDisplayName() : "Someone";
    }
}
