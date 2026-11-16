package com.oak.dms.search.observability;

import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.index.SearchJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Search metrics. The freshness lag (D8) is the headline: how old the oldest due change in the index queue is.
 *
 * <p>Same conventions as {@code DmsMetrics}: every tag value comes from a closed set, there is <b>no</b> tenant, user,
 * query, file or document tag (so nothing here can leak what a user may not see, or explode the number of series), and
 * every series is registered at construction so a dashboard reads 0 rather than "No data" before the first event.
 */
@Component
public class SearchMetrics {

    private static final long GAUGE_CACHE_MS = 5_000;
    private static final long CLUSTER_CACHE_MS = 10_000;

    /** Closed tag sets. */
    static final List<String> MODES = List.of("search", "basic");
    static final List<String> OUTCOMES = List.of("ok", "capped", "error");
    static final List<String> FALLBACK_REASONS = List.of("disabled", "not_ready", "continuing", "engine");
    static final List<String> JOB_KINDS = List.of("document", "folder_subtree", "tenant_purge");
    static final List<String> JOB_OUTCOMES = List.of("indexed", "deleted", "done", "failed");
    static final List<String> BULK_KINDS = List.of("item", "request");
    /** What the browser reports when the preview panel falls back (a closed enum; see SearchTelemetryController). */
    public static final List<String> PREVIEW_REASONS = List.of("unsupported", "failed", "timeout", "policy_blocked");

    private final MeterRegistry registry;
    private final SearchJobRepository jobs;
    private final SearchEngine engine;
    private volatile long cachedAt;
    private volatile double lag;
    private volatile double pending;
    private volatile double failed;
    private volatile long clusterCachedAt;
    private volatile double clusterStatus;

    public SearchMetrics(MeterRegistry registry, SearchJobRepository jobs, SearchEngine engine) {
        this.registry = registry;
        this.jobs = jobs;
        this.engine = engine;
        Gauge.builder("dms.search.index.lag.seconds", this, m -> m.snapshot().lag)
                .description("Age in seconds of the oldest due change waiting to be indexed (0 when the queue is drained)")
                .register(registry);
        Gauge.builder("dms.search.jobs.pending", this, m -> m.snapshot().pending)
                .description("Index jobs waiting or retrying").register(registry);
        Gauge.builder("dms.search.jobs.failed", this, m -> m.snapshot().failed)
                .description("Index jobs parked after exhausting their attempts").register(registry);
        Gauge.builder("dms.search.breaker.state", engine, e -> e.breakerState())
                .description("Search engine circuit breaker: 0 closed (requests flow), 1 open (refused for a while)")
                .register(registry);
        Gauge.builder("dms.search.cluster.status", this, m -> m.cluster())
                .description("Search cluster health: 0 green, 1 yellow, 2 red, 3 unreachable (cached 10 s)")
                .register(registry);
        // Pre-register every closed series so they read 0 before the first event.
        for (String mode : MODES) {
            for (String outcome : OUTCOMES) {
                queryTimer(mode, outcome);
            }
        }
        FALLBACK_REASONS.forEach(r -> fallbackCounter(r));
        for (String kind : JOB_KINDS) {
            for (String outcome : JOB_OUTCOMES) {
                jobsCounter(kind, outcome);
            }
        }
        BULK_KINDS.forEach(k -> bulkCounter(k));
        PREVIEW_REASONS.forEach(r -> previewCounter(r));
        bulkTimer();
        verifyDroppedCounter();
        verifyRounds();
    }

    private SearchMetrics snapshot() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > GAUGE_CACHE_MS) {
            try {
                lag = jobs.oldestDueAt().map(t -> (double) Math.max(0, Duration.between(t, OffsetDateTime.now()).toSeconds()))
                        .orElse(0.0);
                pending = jobs.pendingCount();
                failed = jobs.failedCount();
            } catch (RuntimeException e) {
                // a scrape must never fail because the database blinked
            }
            cachedAt = now;
        }
        return this;
    }

    private double cluster() {
        long now = System.currentTimeMillis();
        if (now - clusterCachedAt > CLUSTER_CACHE_MS) {
            try {
                clusterStatus = switch (engine.health()) {
                    case GREEN -> 0;
                    case YELLOW -> 1;
                    case RED -> 2;
                    case DOWN -> 3;
                };
            } catch (RuntimeException e) {
                clusterStatus = 3;
            }
            clusterCachedAt = now;
        }
        return clusterStatus;
    }

    private Counter jobsCounter(String kind, String outcome) {
        return Counter.builder("dms.search.jobs.processed").tag("kind", kind).tag("outcome", outcome)
                .description("Index jobs processed, by kind and outcome").register(registry);
    }

    public void jobsProcessed(String kind, String outcome, int n) {
        jobsCounter(JOB_KINDS.contains(kind) ? kind : "document", JOB_OUTCOMES.contains(outcome) ? outcome : "failed").increment(n);
    }

    public Timer bulkTimer() {
        return Timer.builder("dms.search.index.bulk").description("Time of one bulk write to the search engine")
                .register(registry);
    }

    public Timer queryTimer(String mode, String outcome) {
        return Timer.builder("dms.search.query").tag("mode", mode).tag("outcome", outcome)
                .description("End-to-end search request time").register(registry);
    }

    /** {@code ok}, or {@code capped} when the over-fetch caps ended the loop early (the user was asked to refine). */
    public Timer queryTimer(String mode) {
        return queryTimer(mode, "ok");
    }

    private Counter fallbackCounter(String reason) {
        return Counter.builder("dms.search.fallback").tag("reason", reason)
                .description("Searches answered by the title-search fallback").register(registry);
    }

    public void fallback(String reason) {
        fallbackCounter(FALLBACK_REASONS.contains(reason) ? reason : "engine").increment();
    }

    private Counter verifyDroppedCounter() {
        return Counter.builder("dms.search.verify.dropped")
                .description("Index candidates removed by the PostgreSQL visibility check (aggregate only)").register(registry);
    }

    public void verifyDropped(int n) {
        verifyDroppedCounter().increment(n);
    }

    private DistributionSummary verifyRounds() {
        return DistributionSummary.builder("dms.search.query.verify.rounds")
                .description("Engine rounds one search needed to fill a page of verified results").register(registry);
    }

    public void verifyRounds(int rounds) {
        verifyRounds().record(rounds);
    }

    private Counter bulkCounter(String kind) {
        return Counter.builder("dms.search.bulk.failures").tag("kind", kind)
                .description("Bulk write failures: single items the engine rejected, or whole requests that failed")
                .register(registry);
    }

    public void bulkFailures(String kind, int n) {
        bulkCounter(BULK_KINDS.contains(kind) ? kind : "request").increment(n);
    }

    private Counter previewCounter(String reason) {
        return Counter.builder("dms.search.preview.failures").tag("reason", reason)
                .description("Times the result preview fell back (unsupported, failed, timeout, blocked by policy)")
                .register(registry);
    }

    public void previewFailure(String reason) {
        if (PREVIEW_REASONS.contains(reason)) {
            previewCounter(reason).increment();
        }
    }
}
