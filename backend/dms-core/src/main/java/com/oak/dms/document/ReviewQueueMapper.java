package com.oak.dms.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.document.dto.ReviewQueueItemResponse;
import com.oak.dms.ocrworker.ProcessingJob;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a flagged {@link DocumentVersion} into a review-queue row
 * (Phase 4b, production prompt 3.13).
 * <p>
 * The interesting part is {@link #summarise}: a queue that lists documents
 * without saying what is wrong with each one makes the reviewer open every
 * document to find out, which is most of the work the queue exists to
 * save. So every row carries one sentence a human can act on.
 * <p>
 * A static helper with no Spring wiring, for the same reason as
 * {@link OcrFieldResultMapper}: no state, nothing to inject, and a unit
 * test can call it directly.
 */
public final class ReviewQueueMapper {

    /** Synthetic reason for a document that never finished OCR at all. */
    public static final String OCR_FAILED = "ocr_failed";

    /** For a failure whose error code could not be recovered at all. */
    static final String UNCLASSIFIED_FAILURE =
            "OCR did not finish for this document. Try running it again.";

    private ReviewQueueMapper() {
    }

    public static ReviewQueueItemResponse toItem(
            Document document, DocumentVersion version, ProcessingJob job, ObjectMapper mapper) {

        JsonNode reasons = readReasons(version, mapper);
        List<String> reasonCodes = strings(reasons.path("reasons"));
        List<String> missing = strings(reasons.path("missing_mandatory"));
        List<String> validation = strings(reasons.path("validation_failures"));
        List<Integer> pages = ints(reasons.path("poor_quality_pages"));

        boolean failed = "failed".equals(version.getOcrStatus());
        String errorCode = failed ? errorCodeOf(job) : null;
        if (failed && !reasonCodes.contains(OCR_FAILED)) {
            // A failed document has no review_reasons at all -- it never
            // got far enough to compute any. Without this it would appear
            // in the queue with an empty "why", which is the one thing the
            // queue must never do.
            reasonCodes = new ArrayList<>(reasonCodes);
            reasonCodes.add(0, OCR_FAILED);
        }

        return new ReviewQueueItemResponse(
                document.getDocumentId(),
                version.getVersionId(),
                document.getTitle(),
                version.getOriginalFilename(),
                version.getMimeType(),
                version.getCreatedAt(),
                version.getOcrStatus(),
                version.isRequiresReview(),
                reasonCodes,
                summarise(failed, errorCode, missing, validation, pages, version),
                missing,
                validation,
                pages,
                errorCode);
    }

    /**
     * One sentence, in the order a reviewer cares about: what stopped the
     * document entirely, then what is missing, then what disagrees, then
     * what looked bad.
     */
    static String summarise(
            boolean failed, String errorCode, List<String> missing,
            List<String> validation, List<Integer> pages, DocumentVersion version) {

        if (failed) {
            // A null code means the job row is gone or its last_error was
            // never in "CODE: detail" shape. The document still failed, so
            // it still needs a sentence -- OcrErrorMessages deliberately
            // answers null for a null code ("no error"), which is the right
            // contract there and the wrong answer here.
            String message = com.oak.dms.ocrworker.OcrErrorMessages.friendlyMessageFor(errorCode);
            return message != null ? message : UNCLASSIFIED_FAILURE;
        }

        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) {
            parts.add(missing.size() == 1
                    ? "1 required field was not found: " + missing.get(0)
                    : missing.size() + " required fields were not found: " + String.join(", ", missing));
        }
        if (!validation.isEmpty()) {
            parts.add(validation.size() == 1
                    ? "a validation check failed: " + readable(validation.get(0))
                    : validation.size() + " validation checks failed: "
                            + String.join(", ", validation.stream().map(ReviewQueueMapper::readable).toList()));
        }
        if (!pages.isEmpty()) {
            parts.add(pages.size() == 1
                    ? "page " + pages.get(0) + " is poor quality"
                    : "pages " + join(pages) + " are poor quality");
        }
        if (version.getReviewReasons() != null
                && version.getReviewReasons().contains("file_repaired")
                && parts.isEmpty()) {
            return "This file was damaged and had to be repaired before reading, "
                    + "so its contents should be double-checked.";
        }
        if (parts.isEmpty()) {
            // Flagged with no detail we can render. Saying so plainly beats
            // an empty cell, and points at a real inconsistency worth
            // investigating rather than hiding it.
            return "Flagged for review, but no reason was recorded.";
        }
        return capitalise(String.join("; ", parts)) + ".";
    }

    /** `subtotal_plus_tax_equals_total` -> `subtotal plus tax equals total`. */
    private static String readable(String rule) {
        return rule == null ? "" : rule.replace('_', ' ');
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String join(List<Integer> values) {
        return String.join(", ", values.stream().map(String::valueOf).toList());
    }

    private static String errorCodeOf(ProcessingJob job) {
        if (job == null) {
            return null;
        }
        // Parsed by OcrErrorMessages, not here. ocr-worker formats every
        // failure as "CODE: detail" (errors.py), and a second copy of that
        // parse is a second thing to get wrong when the format changes.
        return com.oak.dms.ocrworker.OcrErrorMessages.codeOf(job.getLastError());
    }

    private static JsonNode readReasons(DocumentVersion version, ObjectMapper mapper) {
        String raw = version.getReviewReasons();
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception exc) {
            // Unreadable reasons must not hide the document -- it is in the
            // queue because something is wrong with it, and losing the row
            // would be worse than losing the detail.
            return mapper.createObjectNode();
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(item.asText()));
        }
        return out;
    }

    private static List<Integer> ints(JsonNode node) {
        List<Integer> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(item.asInt()));
        }
        return out;
    }
}
