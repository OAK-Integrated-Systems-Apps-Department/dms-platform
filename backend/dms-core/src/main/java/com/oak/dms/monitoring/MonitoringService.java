package com.oak.dms.monitoring;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the monitoring screen's figures from Prometheus.
 * <p>
 * No caching here on purpose. Prometheus answers these in single-digit
 * milliseconds, the screen is PLATFORM_ADMIN-only so there are never many
 * callers, and a cache on a monitoring page is a way to be told calmly that
 * everything was fine thirty seconds ago.
 */
@Service
@RequiredArgsConstructor
public class MonitoringService {

    private final PrometheusClient prometheus;
    private final MonitoringProperties properties;

    private static final String NOT_CONFIGURED =
            "Monitoring is not configured - set dms.monitoring.prometheus-url (PROMETHEUS_URL).";
    private static final String UNREACHABLE =
            "Prometheus did not answer. The metrics below are unavailable, not zero.";

    /** Every tile's current value, in catalogue order. */
    public MonitoringResponse.Snapshot snapshot() {
        if (!prometheus.configured()) {
            return new MonitoringResponse.Snapshot(false, NOT_CONFIGURED, List.of());
        }

        List<MonitoringResponse.Reading> readings = new ArrayList<>();
        for (MonitoringMetric metric : MonitoringMetric.values()) {
            readings.add(new MonitoringResponse.Reading(
                    metric.id(),
                    metric.label(),
                    prometheus.instant(metric.promql()).orElse(null),
                    metric.unit().name(),
                    metric.gauge(),
                    metric.warnAt(),
                    metric.criticalAt()));
        }

        // "Nothing answered at all" is Prometheus being down; one null
        // reading among many is just a quiet metric. Distinguishing them is
        // the difference between "the system is fine" and "we cannot see".
        boolean anything = readings.stream().anyMatch(r -> r.value() != null);
        return new MonitoringResponse.Snapshot(anything, anything ? null : UNREACHABLE, readings);
    }

    /**
     * Friendlier names than the job and instance labels carry. Anything not
     * listed still shows - under its raw label - because a service missing
     * from a status page is worse than one with an ugly name.
     */
    private static final java.util.Map<String, String> SERVICE_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("dms-core", "DMS backend"),
            java.util.Map.entry("postgres", "PostgreSQL"),
            java.util.Map.entry("redis", "Redis"),
            java.util.Map.entry("rabbitmq", "RabbitMQ"),
            java.util.Map.entry("node-exporter", "Host metrics"),
            java.util.Map.entry("cadvisor", "Container metrics"),
            java.util.Map.entry("prometheus", "Prometheus"),
            java.util.Map.entry("grafana", "Grafana"),
            java.util.Map.entry("loki", "Log store"),
            java.util.Map.entry("promtail", "Log shipper"),
            java.util.Map.entry("alertmanager", "Alert delivery"),
            java.util.Map.entry("frontend", "Web app"),
            java.util.Map.entry("keycloak", "Keycloak"),
            java.util.Map.entry("seaweedfs", "Object storage"),
            java.util.Map.entry("gotenberg", "Document converter"),
            java.util.Map.entry("mailpit", "Mail"),
            java.util.Map.entry("clamav", "Virus scanner"));

    /**
     * Which services are answering.
     * <p>
     * Two sources, because one is not enough. {@code up} covers everything
     * with a metrics endpoint - it is a by-product of scraping, so it is
     * free and exact. {@code probe_success} covers the rest: Keycloak,
     * SeaweedFS, Gotenberg, ClamAV, Mailpit and the web app expose no
     * metrics at all, and they are the ones whose failure is least visible,
     * because the DMS keeps serving pages while sign-in or uploads quietly
     * stop working.
     */
    public MonitoringResponse.ServicesResponse services() {
        if (!prometheus.configured()) {
            return new MonitoringResponse.ServicesResponse(false, NOT_CONFIGURED, List.of());
        }

        List<MonitoringResponse.ServiceStatus> services = new ArrayList<>();
        // The blackbox jobs are excluded here and read below instead. Their
        // `up` says the PROBER answered, not that the probed service did -
        // one series per target, all called "blackbox-http", which would put
        // six identical rows in the list beside the real answers they
        // duplicate.
        for (PrometheusClient.Sample sample : prometheus.vector("up{job!~\"blackbox.*\"}")) {
            String job = sample.labels().getOrDefault("job", "unknown");
            services.add(new MonitoringResponse.ServiceStatus(
                    job, SERVICE_NAMES.getOrDefault(job, job), isUp(sample), true));
        }
        for (PrometheusClient.Sample sample : prometheus.vector("probe_success")) {
            String host = hostOf(sample.labels().getOrDefault("instance", ""));
            services.add(new MonitoringResponse.ServiceStatus(
                    host, SERVICE_NAMES.getOrDefault(host, host), isUp(sample), false));
        }

        // Down first - a status page is read to find what is broken, and
        // alphabetical order buries it. Then scraped before probed, then by
        // name, so the list is stable between refreshes.
        services.sort(java.util.Comparator
                .comparing(MonitoringResponse.ServiceStatus::up)
                .thenComparing(MonitoringResponse.ServiceStatus::scraped, java.util.Comparator.reverseOrder())
                .thenComparing(MonitoringResponse.ServiceStatus::label));

        // Prometheus answered if it returned anything at all - `up` always
        // has at least Prometheus's own series.
        boolean reachable = !services.isEmpty();
        return new MonitoringResponse.ServicesResponse(reachable, reachable ? null : UNREACHABLE, services);
    }

    private static boolean isUp(PrometheusClient.Sample sample) {
        return sample.value() != null && sample.value() >= 1.0;
    }

    /**
     * "keycloak" from "http://keycloak:8080/realms/oak-dms" or from
     * "clamav:3310" - the service name is what someone recognises, not the
     * URL the probe happened to use.
     */
    private static String hostOf(String instance) {
        String value = instance;
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value.isBlank() ? instance : value;
    }

    /**
     * The chartable metrics over a window.
     * <p>
     * The step is derived from the window so every chart comes back with
     * roughly {@code chartPoints} points: a 7-day window at a 15-second step
     * would be 40,000 points per series, which is slow to compute, large to
     * send, and unreadable once drawn.
     */
    public MonitoringResponse.SeriesResponse series(Duration window) {
        if (!prometheus.configured()) {
            return new MonitoringResponse.SeriesResponse(false, NOT_CONFIGURED, List.of());
        }

        Instant to = Instant.now();
        Instant from = to.minus(window);
        long step = Math.max(15, window.toSeconds() / Math.max(1, properties.getChartPoints()));

        List<MonitoringResponse.Series> series = new ArrayList<>();
        for (MonitoringMetric metric : MonitoringMetric.values()) {
            if (!metric.chartable()) {
                continue;
            }
            series.add(new MonitoringResponse.Series(
                    metric.id(),
                    metric.label(),
                    metric.unit().name(),
                    prometheus.range(metric.promql(), from, to, step)));
        }

        boolean anything = series.stream().anyMatch(s -> !s.points().isEmpty());
        return new MonitoringResponse.SeriesResponse(anything, anything ? null : UNREACHABLE, series);
    }
}
