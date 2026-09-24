package com.oak.dms.ocrworker;

import java.util.Map;

/**
 * Maps ocr-worker's error-code taxonomy (backend/workers/ocr-worker/src/
 * ocr_worker/errors.py) to a short, user-facing sentence -- the frontend
 * must never see a raw exception string (docs/ocr/failure-analysis.md #9's
 * live finding: a real encrypted PDF and a real corrupt PDF both surfaced
 * opaque internal exception text before this taxonomy existed).
 * {@code processing_jobs.last_error}/the {@code .failed} event's
 * {@code error} field are always formatted as {@code "CODE: message"} by
 * ocr-worker's {@code OcrEngineError.formatted()} -- {@link #codeOf} parses
 * the leading code, {@link #friendlyMessageFor} maps it.
 */
public final class OcrErrorMessages {

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("UNSUPPORTED_FORMAT", "This file format isn't supported for OCR yet."),
            Map.entry("ENCRYPTED_PDF", "This PDF is password-protected and can't be read automatically."),
            Map.entry("CORRUPT_FILE", "This file appears to be damaged and couldn't be opened."),
            Map.entry("TOO_MANY_PAGES", "This document has more pages than OCR can process at once."),
            Map.entry("FILE_TOO_LARGE", "This file is too large for OCR to process."),
            Map.entry("TIMEOUT", "OCR took too long to process this document and was stopped."),
            Map.entry("WORKER_CRASH", "OCR processing was interrupted unexpectedly."),
            Map.entry("ENGINE_ERROR", "OCR ran into an unexpected problem processing this document."),
            Map.entry("LOW_IMAGE_QUALITY", "The image quality was too low for OCR to read reliably.")
    );

    private static final String DEFAULT_MESSAGE = "OCR could not process this document.";

    private OcrErrorMessages() {
    }

    /** Null-safe: returns null for a null/blank input, matching "no failure" -- callers should only call this on a real error string. */
    public static String codeOf(String lastError) {
        if (lastError == null || lastError.isBlank()) {
            return null;
        }
        int colon = lastError.indexOf(':');
        return colon > 0 ? lastError.substring(0, colon).trim() : lastError.trim();
    }

    public static String friendlyMessageFor(String code) {
        if (code == null) {
            return null;
        }
        return MESSAGES.getOrDefault(code, DEFAULT_MESSAGE);
    }
}
