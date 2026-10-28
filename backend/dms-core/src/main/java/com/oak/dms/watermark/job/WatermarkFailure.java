package com.oak.dms.watermark.job;

/**
 * Why a watermark job did not produce a copy.
 *
 * <h2>Two audiences, one enum</h2>
 * Each value carries a stable {@code code} for the job row and the metric
 * tag, and a sentence written for the person who asked. Keeping them
 * together is what stops the two drifting - and keeping the set CLOSED is
 * what makes {@code reason} safe as a metric tag, since a free-text failure
 * string would be unbounded cardinality (several of them would contain file
 * names).
 *
 * <h2>Retryable or not</h2>
 * {@link #retryable()} marks failures caused by the server's own
 * circumstances rather than by the document or the request. The distinction
 * is what the user is told to do: a retryable failure says "try again",
 * which is honest advice only because a failed job releases its idempotency
 * key, so the identical request really will start fresh work. A
 * non-retryable one explains what to change instead, because trying again
 * unchanged would fail the same way.
 */
public enum WatermarkFailure {

    /**
     * The identity the job runs as could not be established - Keycloak was
     * unreachable. Nothing is wrong with the document or the request, and
     * the same request will succeed once it comes back.
     */
    IDENTITY_UNAVAILABLE("WATERMARK_IDENTITY_UNAVAILABLE", true,
            "This watermark couldn't be produced because the sign-in service was unreachable. "
                    + "Please try again."),

    /** The requester is gone, deactivated, or their organisation is suspended. */
    REQUESTER_INACTIVE("WATERMARK_REQUESTER_INACTIVE", false,
            "The person who asked for this watermark is no longer an active user."),

    /** Access was lost between the request and the job running, or the document was deleted. */
    ACCESS_LOST("WATERMARK_ACCESS_LOST", false,
            "You no longer have access to that document, or it has been deleted."),

    /** The stored bytes are not what the version record says they are. */
    CONTENT_MISMATCH("WATERMARK_CONTENT_MISMATCH", false,
            "This file's contents don't match what the system recorded for it, so it wasn't watermarked."),

    /** The document itself - encrypted, signed, corrupt, too many pages. Carries its own message. */
    DOCUMENT_REFUSED("WATERMARK_DOCUMENT_REFUSED", false, null),

    /**
     * The drawing overran its time limit. Retryable in principle, but the
     * same file will almost certainly overrun again - so the message says
     * what is actually true rather than inviting a pointless retry.
     */
    TIMED_OUT("WATERMARK_TIMED_OUT", false,
            "This file took too long to watermark. It may be unusually large or complex."),

    /** Every format thread is abandoned - see WatermarkExecutorConfig. */
    FORMAT_CAPACITY("WATERMARK_FORMAT_CAPACITY", true,
            "The server was too busy to watermark this file. Please try again."),

    /** Storage refused the finished copy. */
    STORAGE_UNAVAILABLE("WATERMARK_STORAGE_UNAVAILABLE", true,
            "The watermarked copy couldn't be stored. Please try again."),

    /** Anything else. Deliberately vague to the user, fully logged for us. */
    UNEXPECTED("WATERMARK_FAILED", true,
            "This watermark couldn't be produced. Please try again.");

    private final String code;
    private final boolean retryable;
    private final String message;

    WatermarkFailure(String code, boolean retryable, String message) {
        this.code = code;
        this.retryable = retryable;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    /** @param detail used by {@link #DOCUMENT_REFUSED}, whose message comes from the format service */
    public String message(String detail) {
        return message != null ? message
                : detail != null && !detail.isBlank() ? detail
                : "This file couldn't be watermarked.";
    }
}
