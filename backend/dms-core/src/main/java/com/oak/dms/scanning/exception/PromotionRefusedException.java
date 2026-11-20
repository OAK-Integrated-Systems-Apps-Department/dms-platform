package com.oak.dms.scanning.exception;

import java.util.Optional;

/**
 * A clean-scanned file was NOT promoted to a document version because, by the time it was ready, the document it
 * belongs to no longer accepts one: it was deleted, locked by a signature, or placed under legal hold, or its current
 * version became signed (DOC-06). The file itself is fine, so this is <b>permanent for this attempt</b> - not a scan
 * failure to retry (a retry would scan the whole file again just to be refused again). The ingestion processor turns
 * it into a terminal {@code failed} result with {@link #toFailureReason()} as its reason and cleans up what the
 * attempt owns; the uploader sees {@link #userMessage} instead of the generic scan failure.
 * <p>
 * Thrown by the {@link com.oak.dms.scanning.service.DocumentPromoter}, never an HTTP error: the ingestion ticket carries
 * the outcome. No stack trace - the code and message say everything there is to know.
 */
public class PromotionRefusedException extends RuntimeException {

    /** Marks a scan result's {@code failure_reason} as a refusal of this kind (the same convention the ticket mapper uses for scanner limits). */
    public static final String REASON_PREFIX = "promotion-refused:";

    private final String code;

    /**
     * @param code    machine-readable, the same codes the request-time guards use where one exists
     *                ({@code DOCUMENT_SIGNATURE_LOCKED}, {@code DOCUMENT_LEGAL_HOLD}, {@code DOCUMENT_HAS_SIGNATURE});
     *                {@code DOCUMENT_UNAVAILABLE} when the document was deleted or is not in the tenant
     * @param message what to tell the uploader
     */
    public PromotionRefusedException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** What is stored on the scan result: the marker, the code, and the message. */
    public String toFailureReason() {
        return REASON_PREFIX + code + ":" + getMessage();
    }

    /** The uploader-facing message from a stored failure reason; empty when the reason is not a promotion refusal. */
    public static Optional<String> userMessage(String failureReason) {
        if (failureReason == null || !failureReason.startsWith(REASON_PREFIX)) {
            return Optional.empty();
        }
        int messageStart = failureReason.indexOf(':', REASON_PREFIX.length());
        return Optional.of(messageStart < 0 ? failureReason.substring(REASON_PREFIX.length())
                : failureReason.substring(messageStart + 1));
    }
}
