package com.oak.dms.search.observability;

import com.oak.dms.search.index.SearchJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Search metrics. The freshness lag (D8) is the headline: how old the oldest due change in the index queue is. Counts
 * here are aggregate only (never per user, query or document) so they cannot leak what a user may not see.
 */
@Component
public class SearchMetrics {

    private static final long GAUGE_CACHE_MS = 5_000;

    private final MeterRegistry registry;
    private final SearchJobRepository jobs;
    private volatile long cachedAt;
    private volatile double lag;
    private volatile double pending;
    private volatile double failed;

    public SearchMetrics(MeterRegistry registry, SearchJobRepository jobs) {
        this.registry = registry;
        this.jobs = jobs;
        Gauge.builder("dms.search.index.lag.seconds", this, m -> m.snapshot().lag)
                .description("Age in seconds of the oldest due change waiting to be indexed (0 when the queue is drained)")
                .register(registry);
        Gauge.builder("dms.search.jobs.pending", this, m -> m.snapshot().pending)
                .description("Index jobs waiting or retrying").register(registry);
        Gauge.builder("dms.search.jobs.failed", this, m -> m.snapshot().failed)
                .description("Index jobs parked after exhausting their attempts").register(registry);
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

    public void jobsProcessed(String kind, String outcome, int n) {
        Counter.builder("dms.search.jobs.processed").tag("kind", kind).tag("outcome", outcome)
                .description("Index jobs processed, by kind and outcome").register(registry).increment(n);
    }

    public Timer bulkTimer() {
        return Timer.builder("dms.search.index.bulk").description("Time of one bulk write to the search engine")
                .register(registry);
    }

    public Timer queryTimer(String mode) {
        return Timer.builder("dms.search.query").tag("mode", mode).description("End-to-end search request time")
                .register(registry);
    }

    public void fallback(String reason) {
        Counter.builder("dms.search.fallback").tag("reason", reason)
                .description("Searches answered by the title-search fallback").register(registry).increment();
    }

    public void verifyDropped(int n) {
        Counter.builder("dms.search.verify.dropped")
                .description("Index candidates removed by the PostgreSQL visibility check (aggregate only)")
                .register(registry).increment(n);
    }
}
