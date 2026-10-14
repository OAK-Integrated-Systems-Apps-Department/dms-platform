package com.oak.dms.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A new comment, or a reply when parentCommentId is set. Mentions are
 * written in the body as {@code @[Display Name](user:<uuid>)} tokens (A5).
 * A reply inherits its thread's workflow instance.
 */
public record CreateCommentRequest(@NotBlank @Size(max = 4000) String body, UUID parentCommentId,
                                   UUID workflowInstanceId) {
}
