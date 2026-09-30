package com.oak.dms.document.dto;

import java.util.List;
import java.util.UUID;

/**
 * Everything the review screen needs to draw itself, in one call
 * (Phase 4c, production prompt 3.13).
 * <p>
 * One call rather than four deliberately. The screen cannot render
 * anything useful until it has the fields, their boxes, the page count
 * and the reasons -- so four round trips would only buy four separate
 * loading states for a page that has nothing to show until the last of
 * them lands. The page IMAGES are separate, because those genuinely do
 * arrive one at a time.
 */
public record DocumentReviewResponse(
        UUID documentId,
        UUID versionId,
        String title,
        String originalFilename,
        String mimeType,

        /** "queued" | "processing" | "completed" | "failed". */
        String ocrStatus,

        /** How many pages the preview endpoint will serve, 1-based count. */
        int pageCount,

        boolean requiresReview,

        /** Machine-readable causes; empty when the document is clean. */
        List<String> reviewReasons,

        /** One sentence, the same one the queue row shows. */
        String reviewSummary,

        /**
         * Every field this version asked OCR for, in schema order,
         * INCLUDING the ones it did not find -- see
         * {@link ReviewFieldResponse}.
         */
        List<ReviewFieldResponse> fields,

        /** Invoice lines, in the order they printed. Read-only in this phase. */
        List<LineItemResponse> lineItems,

        /** Friendly message for a failed document; null when OCR completed. */
        String errorCode,
        String friendlyError) {

    /**
     * One invoice line. `raw` carries the cells as OCR read them so a
     * reviewer can see a parse that went wrong (a quantity of 11 read off
     * a cell that says "1 1") rather than only its result.
     */
    public record LineItemResponse(
            int lineNumber,
            String description,
            java.math.BigDecimal quantity,
            java.math.BigDecimal unitPrice,
            java.math.BigDecimal lineTotal,
            Double confidence,
            Integer sourcePage,
            double[] sourceBbox,
            Object raw) {
    }
}
