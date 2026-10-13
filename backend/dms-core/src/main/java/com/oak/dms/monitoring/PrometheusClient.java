package com.oak.dms.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads Prometheus's HTTP API.
 * <p>
 * Only two calls are used - instant query and range query - and both take
 * their PromQL from {@link MonitoringMetric}, never from a caller.
 * <p>
 * Every failure comes back as an empty result with the reason logged, not as
 * an exception: Prometheus being down must degrade the monitoring screen,
 * never take down the request. It is the one dependency whose job is to tell
 * you things are broken, so it is also the one that must not break anything.
 */
@Slf4j
@Component
public class PrometheusClient {

    private final RestClient client;
    private final boolean configured;

    public PrometheusClient(MonitoringProperties properties, RestClient.Builder builder) {
        this.configured = properties.getPrometheusUrl() != null
                && !properties.getPrometheusUrl().isBlank();
        this.client = configured
                ? builder.baseUrl(properties.getPrometheusUrl().replaceAll("/+$", "")).build()
                : null;
    }

    public boolean configured() {
        return configured;
    }

    /** The single current value, or empty if there is no sample. */
    public java.util.Optional<Double> instant(String promql) {
        Map<String, Object> body = get("/api/v1/query", Map.of("query", promql));
        for (Map<String, Object> result : results(body)) {
            Object value = result.get("value");
            if (value instanceof List<?> pair && pair.size() == 2) {
                Double parsed = toDouble(pair.get(1));
                if (parsed != null) {
                    return java.util.Optional.of(parsed);
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** One sample from an instant query, with the labels that identify it. */
    public record Sample(Map<String, String> labels, Double value) {
    }

    /**
     * Every series an instant query returns, labels included.
     * <p>
     * {@link #instant} collapses to one number, which is right for a figure
     * but useless for a query like {@code up} whose whole point is that
     * there is one series per target and the label says which.
     */
    @SuppressWarnings("unchecked")
    public List<Sample> vector(String promql) {
        Map<String, Object> body = get("/api/v1/query", Map.of("query", promql));
        List<Sample> samples = new ArrayList<>();
        for (Map<String, Object> result : results(body)) {
            Map<String, String> labels = result.get("metric") instanceof Map<?, ?> metric
                    ? (Map<String, String>) metric
                    : Map.of();
            if (result.get("value") instanceof List<?> pair && pair.size() == 2) {
                samples.add(new Sample(labels, toDouble(pair.get(1))));
            }
        }
        return samples;
    }

    /**
     * A time series over a window.
     *
     * @param step seconds between points - bounded by the caller so a long
     *             window cannot turn into a million-point query
     */
    public List<MonitoringResponse.Point> range(String promql, Instant from, Instant to, long step) {
        Map<String, Object> body = get("/api/v1/query_range", Map.of(
                "query", promql,
                "start", from.getEpochSecond(),
                "end", to.getEpochSecond(),
                "step", step));

        List<MonitoringResponse.Point> points = new ArrayList<>();
        for (Map<String, Object> result : results(body)) {
            if (result.get("values") instanceof List<?> values) {
                for (Object entry : values) {
                    if (entry instanceof List<?> pair && pair.size() == 2) {
                        Double parsed = toDouble(pair.get(1));
                        points.add(new MonitoringResponse.Point(
                                ((Number) pair.get(0)).longValue(), parsed));
                    }
                }
            }
            // Only the first series: every query in the catalogue aggregates
            // to one. A second series would mean the PromQL lost its sum().
            break;
        }
        return points;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Map<String, Object> body) {
        if (body == null || !"success".equals(body.get("status"))) {
            return List.of();
        }
        Object data = body.get("data");
        if (data instanceof Map<?, ?> map && map.get("result") instanceof List<?> result) {
            return (List<Map<String, Object>>) result;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, Map<String, Object> params) {
        if (!configured) {
            return null;
        }
        try {
            return client.get()
                    .uri(uriBuilder -> {
                        org.springframework.web.util.UriBuilder builder = uriBuilder.path(path);
                        // Each value goes in as a URI TEMPLATE VARIABLE, not
                        // inline. Spring treats {...} in a queryParam value
                        // as a placeholder, so passing PromQL directly left
                        // `{area="heap"}` unexpanded and the braces, quotes
                        // and spaces unencoded - an invalid URI the client
                        // threw on before sending. The symptom was silent:
                        // every metric WITHOUT label matchers worked, every
                        // metric with them read "No data", and Prometheus
                        // logged nothing because nothing arrived.
                        params.keySet().forEach(name -> builder.queryParam(name, "{" + name + "}"));
                        return builder.build(params);
                    })
                    .retrieve()
                    .body(Map.class);
        } catch (org.springframework.web.client.RestClientException e) {
            // Prometheus unreachable or answering badly. Debug, not warn:
            // when it is down this runs once per metric per refresh, and a
            // warn-level loop would bury the log in the one situation where
            // the log matters. The screen says "no data", which is true.
            log.debug("Prometheus query failed ({}): {}", path, e.getMessage());
            return null;
        } catch (Exception e) {
            // Anything else is a fault in OUR query or URI, not an outage -
            // it will not fix itself and nobody is looking for it, because
            // it renders as an innocent "no data". Say so loudly.
            log.warn("Could not build the Prometheus request for {} ({}): {}",
                    path, params.get("query"), e.toString());
            return null;
        }
    }

    /** Prometheus sends sample values as strings, and "NaN" for a gap. */
    private static Double toDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.toString());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
