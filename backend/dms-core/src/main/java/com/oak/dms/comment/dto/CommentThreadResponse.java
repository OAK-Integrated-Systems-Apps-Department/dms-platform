package com.oak.dms.comment.dto;

import java.util.List;

/** A root comment and its replies, oldest first (threads are two levels deep). */
public record CommentThreadResponse(CommentResponse root, List<CommentResponse> replies) {
}
