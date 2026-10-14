package com.oak.dms.comment.event;

import java.util.UUID;

/** A comment was posted; a reply notifies the author of the thread's root after commit (S9). */
public record CommentCreatedEvent(UUID tenantId, UUID commentId) {
}
