package com.oak.dms.document.dto;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of saving a review: what was recorded, and whether the
 * document is still in the queue afterwards.
 */
public record SaveReviewResponse(
        UUID documentId,
        UUID versionId,
        int accepted,
        int corrected,

        /**
         * Whether the document still needs review after this. False once
         * every reason it was queued for has been settled -- which is what
         * takes it off the queue.
         */
        boolean requiresReview,

        /** Reasons that are still outstanding; empty when the review is done. */
        List<String> remainingReasons) {
}
