package com.oak.dms.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The "how much is waiting" half of the DMS metrics: counts that describe a
 * state rather than an event, so they are gauges and have to be read from the
 * database.
 * <p>
 * Read on a timer into {@link AtomicLong}s that the gauges then publish,
 * rather than queried during the scrape itself. A scrape must be cheap and
 * must not fail: Prometheus scrapes every instance every few seconds, and a
 * query running inside the scrape would put that load on Postgres and make a
 * slow database look like a dead application.
 * <p>
 * Counts are platform-wide, deliberately not per tenant - this is an
 * operations view, and a tag per tenant would grow without bound.
 */
@Slf4j
@Component
public class DmsMetricsGauges {

    /** Scan rows waiting or stuck, by status - the upload backlog. */
    private static final String SCAN_BACKLOG = "dms.scan.backlog";
    /**
     * Stored versions by OCR status - "failed" and "pending" are the ones worth alerting on.
     * <p>
     * FOLLOW-UP (found 2026-10-09, while adding Prometheus monitoring): this reads the real
     * {@code document_versions.ocr_status} column, but nothing in this repo ever writes
     * anything to it other than its {@code not_required} default -
     * {@code backend/workers/ocr-worker/} is an empty placeholder, so every version sits at
     * that default and the "failed"/"pending" series are always 0. Not fixed here: building
     * an OCR worker is its own piece of work, and this change is about observability.
     * The metric, its dashboard panel and the {@code OcrFailuresHigh} alert are deliberately
     * wired to the real column so they start reporting the day that worker lands, with no
     * change needed here. A real fix is the worker itself: set {@code ocr_status} to
     * pending/processing/done/failed as it goes.
     */
    private static final String OCR_STATUS = "dms.ocr.documents";
    private static final String DOCUMENTS_STORED = "dms.documents.stored";
    private static final String STORAGE_BYTES = "dms.storage.bytes";

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final boolean enabled;

    /** One holder per tag value, created on first sight and then only updated. */
    private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();
    /** Tag values seen for each metric, so one that disappears can be zeroed rather than left stale. */
    private final Map<String, Set<String>> seenKeys = new ConcurrentHashMap<>();

    public DmsMetricsGauges(JdbcTemplate jdbc, MeterRegistry registry,
                            @Value("${dms.observability.gauges.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.enabled = enabled;
    }

    /**
     * Seeds the gauges once the context is up, so the dashboard has numbers
     * without waiting a full refresh interval for the first tick.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        // The statuses worth watching, published as 0 before the first query
        // so their panels read "0 waiting" rather than "No data" on a system
        // with nothing stuck. readInto() keeps them accurate from then on.
        set(SCAN_BACKLOG, Tags.of("status", "pending"), 0);
        set(SCAN_BACKLOG, Tags.of("status", "infected"), 0);
        // Likewise for OCR: until an OCR worker exists nothing writes
        // anything but the default status, and a "failed" panel reading
        // "No data" is indistinguishable from a broken scrape.
        set(OCR_STATUS, Tags.of("status", "failed"), 0);
        set(OCR_STATUS, Tags.of("status", "pending"), 0);
        refresh();
    }

    /**
     * Default 30s: slow enough to be invisible next to normal query load,
     * quick enough that a growing backlog shows up before an alert would
     * have fired anyway.
     */
    @Scheduled(fixedDelayString = "${dms.observability.gauges.refresh-ms:30000}",
            initialDelayString = "${dms.observability.gauges.refresh-ms:30000}")
    public void refresh() {
        if (!enabled) {
            return;
        }
        try {
            // Only the statuses that mean "not finished" - a count of every
            // clean row ever would grow forever and says nothing about now.
            readInto(SCAN_BACKLOG, "status", """
                    select status::text as k, count(*) as v
                      from scan_results
                     where not is_deleted and status in ('pending', 'infected')
                     group by status
                    """);
            readInto(OCR_STATUS, "status", """
                    select coalesce(ocr_status, 'unknown') as k, count(*) as v
                      from document_versions
                     where not is_deleted
                     group by coalesce(ocr_status, 'unknown')
                    """);
            set(DOCUMENTS_STORED, Tags.empty(), queryLong(
                    "select count(*) from documents where not is_deleted"));
            set(STORAGE_BYTES, Tags.empty(), queryLong(
                    "select coalesce(sum(size_bytes), 0) from document_versions where not is_deleted"));
        } catch (RuntimeException e) {
            // A database that can't be read is already visible as a down
            // Postgres and a failing health check; losing these gauges for a
            // tick must not take the scheduler's thread down with it.
            log.debug("Could not refresh DMS gauges", e);
        }
    }

    /** Runs a {@code (k, v)} query and publishes one gauge per key, zeroing keys that have gone away. */
    private void readInto(String metric, String tagKey, String sql) {
        Map<String, Long> counts = new java.util.HashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> counts.put(rs.getString("k"), rs.getLong("v")));
        counts.forEach((key, count) -> set(metric, Tags.of(tagKey, key), count));
        // A status that had rows last time and none now must go to 0, not
        // keep its last value - a stale "12 pending" would be read as a stuck
        // queue long after it drained.
        Set<String> seen = seenKeys.computeIfAbsent(metric, key -> ConcurrentHashMap.newKeySet());
        seen.stream()
                .filter(key -> !counts.containsKey(key))
                .forEach(key -> set(metric, Tags.of(tagKey, key), 0));
        seen.addAll(counts.keySet());
    }

    private void set(String metric, Tags tags, long value) {
        String id = metric + "|" + tags.stream()
                .map(t -> t.getKey() + "=" + t.getValue())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        values.computeIfAbsent(id, key -> {
            AtomicLong holder = new AtomicLong();
            registry.gauge(metric, tags, holder);
            return holder;
        }).set(value);
    }

    private long queryLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
