package com.oak.dms.document.dto;

/**
 * One field's OCR extraction outcome, as returned by
 * {@code GET /documents/{id}/ocr-status}. External contract -- the
 * frontend depends on this exact shape ({@code value} is a
 * {@code string | string[] | null}: a plain {@link String} for every field
 * except a {@code multi_select} one, where {@link DocumentServiceImpl
 * #getOcrStatus} decodes ocr-worker's JSON-encoded {@code List<String>}
 * before building this record, so Jackson serializes it as a real JSON
 * array rather than the raw encoded string).
 */
public record OcrFieldResultResponse(
        Object value,
        Double confidence,
        Source source,
        String status) {

    /** null when the field's value has no single spatial anchor (e.g. a regex/free-text-derived value). */
    public record Source(int page, double[] bbox) {
    }
}
