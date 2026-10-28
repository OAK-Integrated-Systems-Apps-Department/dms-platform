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
     * Master switch for the whole OCR feature. OFF by default.
     * <p>
     * Off means nothing is published to the queue and no upload path depends
     * on ocr-worker being reachable: the Scan Documents screen and the review
     * queue are hidden, {@code requestedFields} on an upload is ignored, and
     * {@code OcrEnqueueService} declines every request. A deployment that
     * doesn't run the worker should see no difference from one that never had
     * the feature - in particular, a document still uploads, scans and lands
     * exactly as it would have.
     * <p>
     * Deliberately not {@code @ConditionalOnProperty} on the beans: the
     * watermark module learned that lesson (see {@code WatermarkAvailability}),
     * where making one bean conditional and missing another broke the whole
     * application context for everybody. The flag is enforced where it is
     * visible - the controllers and this one enqueue path.
     */
    private boolean enabled = false;

    /**
     * Most OCR jobs a single tenant may have queued or processing at once.
     * <p>
     * Backpressure, not a quota: over this, new requests are declined and left
     * for {@link OcrBacklogSweeper} rather than rejected, so a 5,000-file
     * import neither fails nor floods RabbitMQ. Per tenant so one busy tenant
     * can't starve the rest.
     */
    private int maxQueuedPerTenant = 2000;

    /**
     * Most files from a single Bulk Import batch that will be queued for OCR.
     * <p>
     * The rest are marked held-back and picked up by the sweeper as the queue
     * drains. A cap rather than unbounded queueing because one import should
     * not be able to own the OCR pipeline for hours.
     */
    private int maxPerImportBatch = 500;

    /**
     * How long a per-job read grant stays valid, in minutes.
     *
     * Short on purpose: the grant is minted when the job is queued and the
     * worker reads the document as its first act, so anything much longer is
     * a window with no use. Long enough that a job sitting behind a short
     * queue still has a usable token when it starts - a job that waits longer
     * than this has stalled, and should be re-queued rather than allowed to
     * read with a stale grant.
     */
    private int jobTokenMinutes = 30;

    /** How long an adhoc (page image / region read) grant lives. Minutes, not hours - it skips the job check. */
    private int adhocTokenMinutes = 2;

    /** Reads one adhoc grant may make, so a review screen can page without minting a grant per page. */
    private int maxAdhocReadsPerVersion = 200;

    /** Largest OCR output JSON accepted. Generous - a long document with line items is a big document. */
    private long maxOutputBytes = 32L * 1024 * 1024;

    /** Largest searchable PDF accepted. A text layer over the original, so broadly the original plus a bit. */
    private long maxRenditionBytes = 128L * 1024 * 1024;

    /**
     * HMAC secret for the per-job read grants ({@link OcrJobTokenCodec}).
     * Blank refuses every internal read rather than signing with an empty key.
     * Its own secret, not shared with impersonation or audit correlation, so a
     * leak or rotation of one doesn't touch the others.
     */
    private String jobTokenSecret = "";

    /** How often {@link OcrBacklogSweeper} looks for versions held back by the cap or backpressure. */
    private long backlogSweepIntervalMs = 30_000;

    /** Most held-back versions the sweeper will queue in one pass. */
    private int backlogSweepBatchSize = 100;

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

    /**
     * Whether rendered page images are held in a small in-process cache
     * ({@code PageImageCache}).
     *
     * <p>On by default: a reviewer paging back and forth through a document
     * otherwise pays a full re-render for a picture that cannot have changed,
     * because a version's bytes are immutable. The switch exists so the cache
     * can be taken out of the picture when diagnosing a stale-image report,
     * without a redeploy.
     */
    private boolean pageImageCacheEnabled = true;

    /**
     * The cache's budget in bytes (default 64 MB).
     *
     * <p>Bytes rather than a count of entries: a 300-DPI A3 page and a 72-DPI
     * thumbnail differ by two orders of magnitude, so a count would not bound
     * memory at all. Nothing is ever written to disk or to Redis - a page
     * image is the document, and everything stored is encrypted at rest, so a
     * plaintext copy is kept only in this process's memory and dies with it.
     */
    private long pageImageCacheBytes = 64L * 1024 * 1024;
}
