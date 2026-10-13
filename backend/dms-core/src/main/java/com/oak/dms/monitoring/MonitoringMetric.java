package com.oak.dms.monitoring;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every query the monitoring screen is allowed to run, by name.
 * <p>
 * An allow-list, deliberately, rather than passing PromQL through from the
 * browser. PromQL is a query language over an engine with no cost ceiling:
 * {@code sum(rate(x[90d]))} across every series will happily occupy
 * Prometheus for a long time, and a caller who can write arbitrary queries
 * can also enumerate every label value in the system. Naming the queries
 * here means the screen can only ask the handful of questions it was built
 * to ask, and each one's cost is known because it was written by hand.
 * <p>
 * The PromQL is kept here next to the label it answers, so a panel that
 * reads wrong can be traced to its query without opening Grafana.
 */
public enum MonitoringMetric {

    /** Scrape targets currently answering - the first thing to look at. */
    TARGETS_UP("Targets up", "sum(up)", Unit.COUNT, false),
    TARGETS_TOTAL("Targets configured", "count(up)", Unit.COUNT, false),

    REQUEST_RATE("Requests", "sum(rate(http_server_requests_seconds_count[5m]))",
            Unit.PER_SECOND, true),

    /**
     * Micrometer tags a failed request {@code outcome="SERVER_ERROR"}. Using
     * outcome rather than {@code status=~"5.."} because it also catches the
     * responses Spring never got to write a status for.
     * <p>
     * {@code or vector(0)} matters here and nowhere else in this list. Until
     * the first 5xx of the process's life there is no such series at all, so
     * the bare sum returns nothing - and this screen deliberately shows "no
     * data" rather than 0 for an absent sample. For every other metric that
     * is the honest answer; for this one it would read as "we cannot tell
     * whether anything is failing" when what is true is "nothing is".
     */
    ERROR_RATE("Server errors",
            "sum(rate(http_server_requests_seconds_count{outcome=\"SERVER_ERROR\"}[5m])) or vector(0)",
            Unit.PER_SECOND, true),

    /**
     * The 95th percentile, not the mean: an average hides the slow tail that
     * is what people actually complain about.
     */
    LATENCY_P95("Response time (p95)",
            "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))",
            Unit.SECONDS, true),

    HEAP_USED("Heap in use", "sum(jvm_memory_used_bytes{area=\"heap\"})", Unit.BYTES, true),
    HEAP_MAX("Heap limit", "sum(jvm_memory_max_bytes{area=\"heap\"})", Unit.BYTES, false),

    CPU_USAGE("CPU", "process_cpu_usage", Unit.RATIO, true),

    DB_CONNECTIONS("Database connections in use", "hikaricp_connections_active", Unit.COUNT, true),

    /** Versions waiting on, or stopped by, the virus scanner. */
    SCAN_BACKLOG("Scan backlog", "sum(dms_scan_backlog)", Unit.COUNT, true),

    DOCUMENTS_STORED("Documents stored", "sum(dms_documents_stored)", Unit.COUNT, false),
    STORAGE_BYTES("Storage used", "sum(dms_storage_bytes)", Unit.BYTES, false),

    // --- Gauges -------------------------------------------------------
    // Only things with a real ceiling belong here. A gauge needs a maximum
    // to be a fraction OF: "72% of the heap" means something, "0.09
    // requests per second" out of what? Rates and counts stay as tiles.
    //
    // Thresholds are the same everywhere - 75% worth noticing, 90% worth
    // acting on - because they all mean the same thing: this resource is
    // running out. A per-metric number would imply a precision nobody has.
    CPU_GAUGE("Backend CPU", "process_cpu_usage", 0.75, 0.90),

    HEAP_GAUGE("Heap used",
            "sum(jvm_memory_used_bytes{area=\"heap\"}) / sum(jvm_memory_max_bytes{area=\"heap\"})",
            0.75, 0.90),

    /** Everything on the machine, not just this process. */
    HOST_CPU_GAUGE("Host CPU", "1 - avg(rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))", 0.75, 0.90),

    /**
     * MemAvailable, not MemFree: Linux lends unused memory to the page
     * cache, so MemFree reads near zero on a perfectly healthy machine and
     * a gauge built on it would sit permanently in the red.
     */
    HOST_MEMORY_GAUGE("Host memory",
            "1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)", 0.75, 0.90),

    /**
     * The fullest real filesystem, rather than one named mount. Mount names
     * differ between a Linux host and Docker Desktop - which reports half a
     * dozen views of the same disk and no plain "/" at all - and the
     * question is "is anything running out of room", which max() answers
     * without naming anything.
     */
    DISK_GAUGE("Disk used",
            "max(1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs|ramfs\"}"
                    + " / node_filesystem_size_bytes{fstype!~\"tmpfs|overlay|squashfs|ramfs\"}))",
            0.75, 0.90),

    /**
     * Connections in use against the pool ceiling. Full means requests are
     * queueing for a connection, which looks like "the database is slow"
     * from every other angle.
     */
    DB_POOL_GAUGE("Database pool", "hikaricp_connections_active / hikaricp_connections_max", 0.75, 0.90);

    /** How the figure should be read - the UI formats, it does not guess. */
    public enum Unit {
        COUNT, PER_SECOND, SECONDS, BYTES, RATIO
    }

    private final String label;
    private final String promql;
    private final Unit unit;
    private final boolean chartable;
    private final Double warnAt;
    private final Double criticalAt;

    MonitoringMetric(String label, String promql, Unit unit, boolean chartable) {
        this(label, promql, unit, chartable, null, null);
    }

    /** A gauge: a RATIO with a ceiling, and the two points worth colouring. */
    MonitoringMetric(String label, String promql, double warnAt, double criticalAt) {
        this(label, promql, Unit.RATIO, true, warnAt, criticalAt);
    }

    MonitoringMetric(String label, String promql, Unit unit, boolean chartable,
                     Double warnAt, Double criticalAt) {
        this.label = label;
        this.promql = promql;
        this.unit = unit;
        this.chartable = chartable;
        this.warnAt = warnAt;
        this.criticalAt = criticalAt;
    }

    /** Non-null only for a gauge - the fraction at which this stops being fine. */
    public Double warnAt() {
        return warnAt;
    }

    public Double criticalAt() {
        return criticalAt;
    }

    /** A bounded 0..1 reading, drawn as a dial rather than a number. */
    public boolean gauge() {
        return warnAt != null;
    }

    public String label() {
        return label;
    }

    public String promql() {
        return promql;
    }

    public Unit unit() {
        return unit;
    }

    /**
     * Whether a range query over this makes sense. A limit or a running
     * total is flat or monotonic by nature, so charting it says nothing -
     * those are tiles only.
     */
    public boolean chartable() {
        return chartable;
    }

    /** Lower-case, so the wire name is {@code request_rate} rather than REQUEST_RATE. */
    public String id() {
        return name().toLowerCase();
    }

    public static Optional<MonitoringMetric> byId(String id) {
        return Arrays.stream(values())
                .filter(metric -> metric.id().equalsIgnoreCase(id))
                .findFirst();
    }
}
