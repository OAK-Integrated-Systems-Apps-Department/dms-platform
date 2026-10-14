package com.oak.dms.comment.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A comment the caller was mentioned in. canOpenDocument is the caller's
 * current docm.documents.view; without it they read the comment but get no
 * link to the document (decision 10).
 */
public record MentionResponse(UUID mentionId, CommentResponse comment, UUID documentId, String documentTitle,
                              boolean canOpenDocument, OffsetDateTime createdAt) {
}
