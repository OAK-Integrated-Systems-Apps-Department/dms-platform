package com.oak.dms.ocrworker;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code dms.ocr.*} configuration keys.
 * <p>
 * Until Phase 2 there was exactly one of these, a {@code @Value}-injected
 * boolean on {@link com.oak.dms.document.DocumentServiceImpl}. The
 * reconciler adds four more, which is the point at which a properties class
 * is worth having -- mirroring
 * {@link com.oak.dms.metadata.cache.SchemaCacheProperties}, the only other
 * {@code dms.*} properties class.
 */
@Component
@ConfigurationProperties(prefix = "dms.ocr")
@Getter
@Setter
public class OcrProperties {

    /**
     * Re-apply OCR fields on every {@code GET /ocr-status} poll.
     * <p>
     * A safety net, not the primary path -- see the long comment in
     * {@code application.yml} and {@code DocumentServiceImpl#getOcrStatus}.
     * Phase 2 retires it in favour of {@link OcrFieldsReconciler}, gated on
     * a 50-upload concurrent run with this set to false.
     */
    private boolean pollReapplyEnabled = true;

    /** Master switch for {@link OcrFieldsReconciler}. */
    private boolean reconcilerEnabled = true;

    /**
     * How often the reconciler ticks.
     * <p>
     * A minute rather than the outbox relay's two seconds: this is a
     * backstop for a lost message, not a delivery mechanism, and the
     * default Spring scheduler is single-threaded and shared with
     * {@code OutboxRelay}.
     */
    private long reconcilerIntervalMs = 60_000;

    /**
     * How long after a job finished a still-unapplied version counts as
     * a real miss rather than a race.
     * <p>
     * Must comfortably exceed the normal path's latency: ocr-worker writes
     * the field results and the outbox row, then dms-core's relay polls
     * every 2s, then the listener runs. Ten minutes is far beyond that, so
     * a warning from this reconciler genuinely means the event never
     * arrived -- which is the only thing that makes the warning worth
     * reading.
     */
    private long staleAfterMinutes = 10;

    /**
     * Versions reconciled per tick.
     * <p>
     * Bounded because the tick shares one scheduler thread with
     * {@code OutboxRelay}'s 2s poll, and each version costs a schema
     * lookup plus a delete-then-insert per field. In a healthy system the
     * scan returns nothing at all; this only matters while draining a
     * backlog after a real outage.
     */
    private int batchSize = 50;

    /**
     * Where ocr-worker answers. It publishes no port, so this is a
     * container-network address; there is no public one by design.
     */
    private String workerBaseUrl = "http://ocr-worker:8090";

    /**
     * The shared secret every ocr-worker route requires
     * ({@code X-Internal-Token}) -- the same value ocr-worker reads from
     * its own {@code INTERNAL_SERVICE_TOKEN}. Defense in depth behind the
     * network isolation, not instead of it.
     */
    private String internalServiceToken = "";

    /**
     * Preview render DPI for the review screen, or null to let ocr-worker
     * use its own {@code OCR_PREVIEW_DPI}. Present so the resolution the
     * reviewer sees can be tuned without redeploying the worker.
     */
    private Integer previewDpi = null;
}
