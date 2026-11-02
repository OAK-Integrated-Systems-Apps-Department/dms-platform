package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Confirms a review session is open (V119) - the caller's own decided task, the document it concerns,
 * and when the soft lock on the rest of the stage expires if not renewed (see
 * {@code WorkflowTaskServiceImpl}'s own doc comment on {@code openForReview}/{@code renewReviewLock}).
 */
public record ReviewLockResponse(
        UUID taskId,
        UUID stageInstanceId,
        UUID documentId,
        UUID documentVersionId,
        String decision,
        String decisionReason,
        OffsetDateTime actedAt,
        OffsetDateTime reviewLockExpiresAt
) {
}
