package com.oak.dms.document.dto;

import java.util.List;

/**
 * What the reviewer decided, submitted when they finish a document.
 * <p>
 * Sent as one batch rather than a call per field on purpose: a review is
 * one act of judgement over a whole document, and the fields a reviewer
 * accepted only mean something alongside the ones they corrected. A
 * per-field endpoint would also let a half-saved review leave the
 * document in a state nobody chose.
 */
public record SaveReviewRequest(List<Decision> decisions) {

    /**
     * @param fieldKey which field.
     * @param action   "accepted" or "corrected".
     * @param value    what the reviewer settled on. Ignored for an
     *                 acceptance -- the server uses OCR's own value there
     *                 rather than trusting the client to echo it back,
     *                 since a client that echoed it wrongly would silently
     *                 record a correction as an acceptance.
     * @param source   "typed" or "region" for a correction; null for an
     *                 acceptance. The distinction matters: a region
     *                 correction says OCR could read the text once pointed
     *                 at the right place, which is a different defect from
     *                 OCR misreading it.
     */
    public record Decision(String fieldKey, String action, String value, String source) {
    }
}
