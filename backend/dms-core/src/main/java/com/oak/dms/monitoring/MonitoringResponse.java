package com.oak.dms.monitoring;

import java.util.List;

/**
 * What the monitoring screen gets back.
 * <p>
 * Every figure carries its own {@code available} flag rather than the whole
 * response failing: one exporter being down is normal, and it should grey
 * out one tile, not blank the page that would have told you which exporter
 * it was.
 */
public final class MonitoringResponse {

    private MonitoringResponse() {
    }

    /**
     * One current figure.
     *
     * @param value null when Prometheus has no sample for it - a target that
     *              is down, or a metric nothing has recorded yet. The UI
     *              shows that as "no data", which is true, rather than 0,
     *              which would be a lie.
     * @param unit  how to format it: COUNT, PER_SECOND, SECONDS, BYTES, RATIO
     */
    /**
     * @param gauge      true when this is a bounded 0..1 reading meant to be
     *                   drawn as a dial rather than a number
     * @param warnAt     the fraction at which it stops being fine; null unless gauge
     * @param criticalAt the fraction at which it needs acting on; null unless gauge
     */
    public record Reading(String id, String label, Double value, String unit,
                          boolean gauge, Double warnAt, Double criticalAt) {
    }

    /** @param reachable false when Prometheus itself could not be asked - the whole screen is then stale, and says so. */
    public record Snapshot(boolean reachable, String error, List<Reading> readings) {
    }

    /** @param at epoch seconds, as Prometheus returns them */
    public record Point(long at, Double value) {
    }

    public record Series(String id, String label, String unit, List<Point> points) {
    }

    public record SeriesResponse(boolean reachable, String error, List<Series> series) {
    }

    /**
     * One service and whether it is answering.
     *
     * @param scraped true when this comes from a real metrics endpoint,
     *                false when it is only a reachability probe. The
     *                difference is worth showing: a scraped service is
     *                reporting on itself, a probed one has merely been
     *                found listening.
     */
    public record ServiceStatus(String id, String label, boolean up, boolean scraped) {
    }

    public record ServicesResponse(boolean reachable, String error, List<ServiceStatus> services) {
    }

    /**
     * One dependency as the backend itself sees it, from Actuator.
     *
     * @param status UP, DOWN, OUT_OF_SERVICE or UNKNOWN - Actuator's own
     *               vocabulary, passed through rather than reduced to a
     *               boolean, because OUT_OF_SERVICE is a deliberate state
     *               and flattening it to "down" would read as a fault
     * @param detail one short line, or null - disk space figures, or the
     *               reason something is down. Never the raw detail map.
     */
    public record Dependency(String id, String label, String status, String detail) {
    }

    /** @param status the aggregate, which is DOWN if any contributor is */
    public record DependencyHealth(String status, List<Dependency> dependencies) {
    }

    /**
     * One log line.
     *
     * @param at    epoch milliseconds
     * @param level TRACE..FATAL when the line declared one, otherwise null -
     *              never guessed
     */
    public record LogLine(long at, String level, String line) {
    }

    /** @param truncated true when more matched than were returned */
    public record LogsResponse(boolean reachable, String error, List<LogLine> lines, boolean truncated) {
    }

    /**
     * One alert rule and where it stands.
     *
     * @param state    firing, pending (condition true but not for long enough
     *                 yet) or inactive
     * @param severity as the rule labels it: critical, warning, or null
     * @param detail   for a firing or pending rule, the description rendered
     *                 with the actual value ("7.2% are returning 5xx"); null
     *                 otherwise, because an inactive rule's description is
     *                 still an unexpanded template
     * @param since    epoch seconds the condition first became true; null
     *                 unless firing or pending
     * @param broken   true when the rule itself failed to evaluate. That is
     *                 worse than inactive and must not look the same: a rule
     *                 that cannot run will never fire.
     * @param blind    true when the metric the rule reads has no series at all.
     *                 The rule evaluates fine - it just has nothing to compare -
     *                 so Prometheus reports it as healthy and inactive, which
     *                 reads as "all clear" when the truth is "cannot see".
     * @param requires the selector the rule reads, for saying what is missing
     */
    public record AlertRule(String name, String state, String severity, String component,
                            String summary, String detail, Long since, boolean broken,
                            boolean blind, String requires) {
    }

    public record AlertsResponse(boolean reachable, String error, List<AlertRule> rules) {
    }
}
