package com.oak.dms.document.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row of the review queue (Phase 4b, production prompt 3.13):
 * "every failed or requires_review document, with the reason".
 * <p>
 * The reason is the point. A queue that lists documents without saying
 * what is wrong with each one makes the reviewer open every single
 * document to find out, which is most of the work the queue exists to
 * save. So {@link #reasons()} is always populated for a row that is in
 * the queue -- a failed document carries its error code, and a
 * requires_review one carries the rule that fired.
 */
public record ReviewQueueItemResponse(
        UUID documentId,
        UUID versionId,
        String title,
        String originalFilename,
        String mimeType,
        OffsetDateTime createdAt,

        /** "failed" | "completed". A failed document is in the queue because OCR did not finish. */
        String ocrStatus,

        /** True when OCR completed but a rule fired. False for a purely failed document. */
        boolean requiresReview,

        /**
         * Machine-readable causes, e.g. {@code missing_mandatory_field},
         * {@code validation_failed}, {@code poor_image_quality},
         * {@code file_repaired}, or {@code ocr_failed} for a failure.
         * Drives filtering and iconography; never shown raw to a user.
         */
        List<String> reasons,

        /**
         * One sentence a human can act on, built from the reasons and their
         * detail -- "2 required fields were not found: invoice_number,
         * amount". This is what the queue actually displays.
         */
        String summary,

        /** Mandatory fields that did not come back as `extracted`; empty when none. */
        List<String> missingMandatory,

        /** Validation rules that failed; empty when none. */
        List<String> validationFailures,

        /** 1-indexed pages flagged poor quality; empty when none. */
        List<Integer> poorQualityPages,

        /**
         * The classified error code for a failed document (ENCRYPTED_PDF,
         * CORRUPT_FILE, TIMEOUT, ...), or null when OCR completed. The
         * friendly message for it is resolved in the UI -- see
         * {@code OcrErrorMessages}.
         */
        String errorCode) {
}
