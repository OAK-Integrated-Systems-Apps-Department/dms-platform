package com.oak.dms.monitoring;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Which alert rules are firing, read from Prometheus.
 * <p>
 * The rules live in infra/monitoring/prometheus/alerts.yml and are evaluated
 * by Prometheus itself. Nothing delivers them: there is no Alertmanager and no
 * email or chat route, and the one place they were meant to be seen was
 * Grafana's alerting screen, which this application deliberately does not
 * expose. Without this, a rule could fire and nobody would ever find out.
 * <p>
 * Prometheus's response is parsed defensively and never trusted to have a
 * field: a rule with a missing label shows up with that label blank rather
 * than failing the whole list, because a half-readable alert list is better
 * than none during the incident it exists for.
 */
@Service
@RequiredArgsConstructor
public class AlertsService {

    private static final String UNREACHABLE =
            "Prometheus did not answer. Whether anything is firing is unknown, not clear.";

    private final PrometheusClient prometheus;

    public MonitoringResponse.AlertsResponse alerts() {
        if (!prometheus.configured()) {
            return new MonitoringResponse.AlertsResponse(false,
                    "Monitoring is not configured - set dms.monitoring.prometheus-url (PROMETHEUS_URL).",
                    List.of());
        }
        Map<String, Object> body = prometheus.alertRules();
        if (body == null || !"success".equals(body.get("status"))
                || !(body.get("data") instanceof Map<?, ?> data)
                || !(data.get("groups") instanceof List<?> groups)) {
            return new MonitoringResponse.AlertsResponse(false, UNREACHABLE, List.of());
        }

        List<MonitoringResponse.AlertRule> rules = new ArrayList<>();
        for (Object groupObj : groups) {
            if (groupObj instanceof Map<?, ?> group && group.get("rules") instanceof List<?> groupRules) {
                for (Object ruleObj : groupRules) {
                    if (ruleObj instanceof Map<?, ?> rule) {
                        rules.add(toRule(rule));
                    }
                }
            }
        }

        rules = markBlind(rules);

        // Most urgent first. A broken rule sorts with the firing ones: it is
        // an alert that cannot alert, which is its own kind of emergency.
        rules.sort(Comparator
                .comparingInt(AlertsService::rank)
                .thenComparing(MonitoringResponse.AlertRule::name));
        return new MonitoringResponse.AlertsResponse(true, null, rules);
    }

    /**
     * Flags the rules whose input does not exist.
     * <p>
     * Only quiet rules are checked: a rule that is firing or pending has, by
     * definition, found data. And each distinct selector is asked once - the
     * two disk rules read the same series - so this costs one cheap
     * {@code count()} per distinct input, not one per rule.
     */
    private List<MonitoringResponse.AlertRule> markBlind(List<MonitoringResponse.AlertRule> rules) {
        Map<String, Boolean> hasData = new java.util.HashMap<>();
        List<MonitoringResponse.AlertRule> out = new ArrayList<>(rules.size());
        for (MonitoringResponse.AlertRule rule : rules) {
            boolean quiet = !rule.broken() && "inactive".equals(rule.state());
            String selector = rule.requires();
            if (!quiet || selector == null) {
                out.add(rule);
                continue;
            }
            boolean present = hasData.computeIfAbsent(selector,
                    s -> !prometheus.vector("count(" + s + ")").isEmpty());
            out.add(present ? rule : withBlind(rule));
        }
        return out;
    }

    private static MonitoringResponse.AlertRule withBlind(MonitoringResponse.AlertRule rule) {
        return new MonitoringResponse.AlertRule(rule.name(), rule.state(), rule.severity(),
                rule.component(), rule.summary(), rule.detail(), rule.since(), rule.broken(),
                true, rule.requires());
    }

    private static int rank(MonitoringResponse.AlertRule rule) {
        if (rule.broken()) {
            return 1;
        }
        // A rule that cannot see its input is not an emergency, but it is not
        // "fine" either, so it sorts ahead of the genuinely quiet ones.
        if (rule.blind()) {
            return 4;
        }
        int severity = "critical".equals(rule.severity()) ? 0 : 1;
        return switch (rule.state()) {
            case "firing" -> severity;          // 0 critical, 1 warning
            case "pending" -> 2 + severity;     // 2 critical, 3 warning
            default -> 5;
        };
    }

    @SuppressWarnings("unchecked")
    private static MonitoringResponse.AlertRule toRule(Map<?, ?> rule) {
        String name = string(rule.get("name"));
        String state = string(rule.get("state"));
        Map<String, Object> labels = rule.get("labels") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> annotations =
                rule.get("annotations") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        String detail = null;
        Long since = null;
        if (rule.get("alerts") instanceof List<?> alerts && !alerts.isEmpty()) {
            // One rule can have several active alerts (one per instance). The
            // earliest start is when the problem began; the first alert's
            // rendered description carries the actual value.
            for (Object alertObj : alerts) {
                if (alertObj instanceof Map<?, ?> alert) {
                    Long at = epoch(alert.get("activeAt"));
                    if (at != null && (since == null || at < since)) {
                        since = at;
                    }
                    if (detail == null && alert.get("annotations") instanceof Map<?, ?> rendered) {
                        detail = nullIfBlank(string(rendered.get("description")));
                    }
                }
            }
        }

        boolean broken = !"ok".equals(string(rule.get("health"))) && rule.get("health") != null;
        // Read from the rule's own annotations. Bounded, because it is about
        // to be placed inside a query and a rule file is not a place to
        // expect a novel.
        String requires = nullIfBlank(string(annotations.get("requires")));
        if (requires != null && requires.length() > 300) {
            requires = null;
        }
        return new MonitoringResponse.AlertRule(
                name,
                state.isEmpty() ? "inactive" : state,
                nullIfBlank(string(labels.get("severity"))),
                nullIfBlank(string(labels.get("component"))),
                nullIfBlank(string(annotations.get("summary"))),
                detail,
                since,
                broken,
                false,
                requires);
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Prometheus sends an RFC 3339 timestamp; the screen wants epoch seconds. */
    private static Long epoch(Object value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value.toString()).toEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }
}
