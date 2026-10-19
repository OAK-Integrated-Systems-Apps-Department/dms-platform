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
import com.oak.dms.workflow.helper.WorkflowRecipients;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Mention and reply notifications, written through the shared notification
 * service BEFORE_COMMIT, in the comment's own transaction (3.2, N8). A mention
 * sets notified_at once its notification is written; a reply notifies the
 * thread's root author unless they wrote the reply or are mentioned in it
 * (they get the mention notification instead).
 */
@Component
@RequiredArgsConstructor
public class CommentNotificationListener {

    private final DocumentCommentRepository commentRepository;
    private final CommentMentionRepository mentionRepository;
    private final WorkflowRecipients recipients;
    private final CommentNotificationMailer mailer;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMentioned(CommentMentionedEvent event) {
        notifyMentioned(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCreated(CommentCreatedEvent event) {
        notifyReply(event);
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
            mailer.sendMentioned(to, author, comment.getDocumentId(), title, text);
            // The durable in-app notification was written in this transaction.
            mentionRepository.markNotified(mention.getMentionId(), OffsetDateTime.now());
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
        mailer.sendReply(to, author, reply.getDocumentId(), recipients.documentTitle(reply.getDocumentId()),
                MentionParser.toPlainText(reply.getBody()));
    }

    private String name(UUID userId, UUID tenantId) {
        User u = recipients.activeUsers(List.of(userId), tenantId).get(userId);
        return u != null && u.getDisplayName() != null ? u.getDisplayName() : "Someone";
    }
}
