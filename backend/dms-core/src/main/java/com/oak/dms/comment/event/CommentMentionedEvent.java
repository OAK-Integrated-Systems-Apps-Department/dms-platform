package com.oak.dms.comment.event;

import java.util.List;
import java.util.UUID;

/** New mentions on a comment - each mentioned user is mailed after commit and notified_at set (S9). */
public record CommentMentionedEvent(UUID tenantId, UUID commentId, List<UUID> mentionIds) {
}
