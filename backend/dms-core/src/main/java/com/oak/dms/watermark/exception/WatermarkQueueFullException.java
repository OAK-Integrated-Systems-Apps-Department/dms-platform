package com.oak.dms.watermark.exception;

import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * Everything that can watermark is busy: the bounded job queue refused the
 * work.
 * <p>
 * A distinct type rather than a plain {@link WatermarkException} because it
 * is the one refusal that comes with a concrete answer to "what should I do
 * then?" - wait this long and ask again. The controller turns
 * {@link #getRetryAfter()} into the {@code Retry-After} header, so a client
 * doesn't have to guess or hammer.
 * <p>
 * 503 rather than 429: nothing is wrong with the request and the caller is
 * not over any limit of their own. The server is simply saturated, and will
 * accept the identical request shortly.
 */
public class WatermarkQueueFullException extends WatermarkException {

    private final transient Duration retryAfter;

    public WatermarkQueueFullException(Duration retryAfter) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "WATERMARK_QUEUE_FULL",
                "The server is busy producing other watermarks. Please try again in a moment.");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
