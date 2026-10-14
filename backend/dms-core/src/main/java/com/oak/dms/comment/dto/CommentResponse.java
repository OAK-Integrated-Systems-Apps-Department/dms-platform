package com.oak.dms.comment.dto;

import com.oak.dms.workflow.dto.UserRef;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A deleted comment keeps its place in the thread with body "[deleted]" and no mentions. */
public record CommentResponse(
        UUID commentId,
        UUID documentId,
        UUID workflowInstanceId,
        UUID parentCommentId,
        String body,
        UserRef author,
        OffsetDateTime createdAt,
        OffsetDateTime editedAt,
        boolean deleted,
        List<CommentMentionResponse> mentions
) {
}
