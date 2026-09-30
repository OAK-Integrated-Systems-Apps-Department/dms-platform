package com.oak.dms.document.dto;

/**
 * One field on the review screen: what the schema calls it, what OCR made
 * of it, and where on the page that came from.
 * <p>
 * A list rather than a map keyed by field_key, and built from the
 * version's REQUESTED fields rather than from the result rows, for one
 * reason that matters: a mandatory field OCR never found is the single
 * most important thing on this screen, and it is exactly the field with
 * no result row to iterate over. Driving the list off the rows would drop
 * it silently -- the reviewer would see a tidy screen of everything that
 * worked and no sign of the thing that sent them here.
 */
public record ReviewFieldResponse(
        String fieldKey,
        String label,
        boolean mandatory,

        /**
         * The schema data type ("text", "date", "multi_select", ...).
         * Carried because a multi_select value is a LIST, and a corrected
         * one has to be split back into one before it is saved -- joining
         * it for display and then saving the joined string would collapse
         * two tags into one tag literally named "finance, q3".
         */
        String dataType,

        /** String, or List&lt;String&gt; for a multi_select field; null when not found. */
        Object value,

        /** What OCR read before parsing, when that differed from `value`; null otherwise. */
        String rawValue,

        Double confidence,

        /** "extracted" | "low_confidence" | "not_found". */
        String status,

        /** Page (1-based) and normalized bbox, or null when the value has no single location. */
        OcrFieldResultResponse.Source source) {
}
