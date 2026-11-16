package com.oak.dms.search.extract;

/**
 * The outcome of reading one version's text. {@code PENDING} means a source is still being produced (OCR or the
 * preview PDF): the index document is written without a body now, and the artifact's own trigger re-queues it when
 * the source completes. {@code UNAVAILABLE} is final for these inputs (the document stays searchable by title, file
 * name and metadata). The reason is a short code, never document text.
 */
public record ExtractedText(Status status, String reason, String source, String text) {

    public enum Status { READY, PENDING, UNAVAILABLE }

    public static ExtractedText ready(String source, String text) {
        return new ExtractedText(Status.READY, null, source, text);
    }

    public static ExtractedText pending() {
        return new ExtractedText(Status.PENDING, null, null, "");
    }

    public static ExtractedText unavailable(String reason) {
        return new ExtractedText(Status.UNAVAILABLE, reason, null, "");
    }

    public boolean cacheable() {
        return status != Status.PENDING;
    }
}
