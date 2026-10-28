package com.oak.dms.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Container logs for the System Monitoring screen, read from Loki.
 * <p>
 * Everything a caller supplies is treated as hostile, because it ends up
 * inside a LogQL query. The service name is checked against the shape a
 * compose service name can have, the level against a fixed set, and the
 * search text is escaped into a quoted string - never concatenated as
 * query syntax. A caller who could write their own selector could read the
 * logs of any container, which is the one thing this is not for.
 * <p>
 * The same failure policy as {@link PrometheusClient}: Loki being down
 * degrades the log view to a message, never the request.
 */
@Slf4j
@Service
public class LogsService {

    /** A compose service name: lower-case, digits, hyphens. Nothing that is LogQL syntax. */
    private static final Pattern SERVICE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");

    /**
     * The windows the screen offers. Named rather than free-form for the
     * same reason the metrics windows are: an unbounded range is an
     * unbounded query.
     */
    private static final Map<String, Duration> WINDOWS = Map.of(
            "15m", Duration.ofMinutes(15),
            "1h", Duration.ofHours(1),
            "6h", Duration.ofHours(6),
            "24h", Duration.ofHours(24),
            "7d", Duration.ofDays(7));

    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 200;
    /** A single stack trace can be enormous; the screen is not a file viewer. */
    private static final int MAX_LINE_CHARS = 4000;

    private final RestClient client;
    private final boolean configured;

    public LogsService(MonitoringProperties properties, RestClient.Builder builder) {
        String url = properties.getLokiUrl();
        this.configured = url != null && !url.isBlank();
        this.client = configured ? builder.baseUrl(url.replaceAll("/+$", "")).build() : null;
    }

    public MonitoringResponse.LogsResponse logs(String service, String level, String search,
                                                String range, Integer limit) {
        if (service == null || !SERVICE.matcher(service).matches()) {
            // A 200 with a message rather than a 400: the screen builds this
            // from a list it was handed, so a bad value is our bug, and the
            // message is what a developer needs to find it.
            return new MonitoringResponse.LogsResponse(false, "Unknown service.", List.of(), false);
        }
        if (!configured) {
            return new MonitoringResponse.LogsResponse(false,
                    "Logs are not configured - set dms.monitoring.loki-url (LOKI_URL).", List.of(), false);
        }

        int wanted = Math.min(Math.max(limit == null ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        Duration window = WINDOWS.getOrDefault(range, Duration.ofHours(1));
        Instant to = Instant.now();
        Instant from = to.minus(window);

        String query = buildQuery(service, level, search);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = client.get()
                    .uri(uri -> uri.path("/loki/api/v1/query_range")
                            // Template variables, not inline values: braces in
                            // a query are URI-template placeholders to Spring,
                            // and LogQL is full of them. Passed inline, the URI
                            // is rejected before it is sent - the same failure
                            // that once made six Prometheus metrics read "No
                            // data" with nothing in any log.
                            .queryParam("query", "{query}")
                            .queryParam("start", "{start}")
                            .queryParam("end", "{end}")
                            .queryParam("limit", "{limit}")
                            .queryParam("direction", "backward")
                            .build(Map.of(
                                    "query", query,
                                    "start", nanos(from),
                                    "end", nanos(to),
                                    // One more than asked for, so "there is
                                    // more" is known rather than guessed.
                                    "limit", wanted + 1)))
                    .retrieve()
                    .body(Map.class);
            return parse(body, wanted);
        } catch (RestClientException e) {
            log.debug("Loki query failed: {}", e.getMessage());
            return new MonitoringResponse.LogsResponse(false,
                    "The log store did not answer. Logs are unavailable, not empty.", List.of(), false);
        } catch (Exception e) {
            log.warn("Could not build the Loki request for {}: {}", service, e.toString());
            return new MonitoringResponse.LogsResponse(false, "Could not read logs.", List.of(), false);
        }
    }

    /**
     * {service="x"} plus, only if asked, a level filter and a text filter.
     * Both optional parts are rebuilt from validated pieces; nothing from the
     * caller reaches the query as syntax.
     */
    static String buildQuery(String service, String level, String search) {
        StringBuilder query = new StringBuilder("{service=\"").append(service).append("\"}");
        if (level != null && LEVELS.contains(level.toUpperCase())) {
            // Matches the level LABEL Promtail extracted. A line that
            // declared no level has no label and is excluded here, which is
            // right: filtering to ERROR should not include lines that merely
            // never said what they were.
            query.append(" | level=\"").append(level.toUpperCase()).append('"');
        }
        if (search != null && !search.isBlank()) {
            // |= is a plain substring match, not a regex, so the text needs
            // quoting only - no regex escaping. Backslash first, or the
            // escape we add for the quote would itself be escaped.
            String escaped = search.strip()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", " ")
                    .replace("\r", " ");
            if (escaped.length() > 200) {
                escaped = escaped.substring(0, 200);
            }
            query.append(" |= \"").append(escaped).append('"');
        }
        return query.toString();
    }

    @SuppressWarnings("unchecked")
    private MonitoringResponse.LogsResponse parse(Map<String, Object> body, int wanted) {
        List<MonitoringResponse.LogLine> lines = new ArrayList<>();
        if (body != null && body.get("data") instanceof Map<?, ?> data
                && data.get("result") instanceof List<?> streams) {
            for (Object streamObj : streams) {
                Map<String, Object> stream = (Map<String, Object>) streamObj;
                Map<String, String> labels = stream.get("stream") instanceof Map<?, ?> m
                        ? (Map<String, String>) m : Map.of();
                String level = labels.get("level");
                if (stream.get("values") instanceof List<?> values) {
                    for (Object entry : values) {
                        if (entry instanceof List<?> pair && pair.size() == 2) {
                            long millis = Long.parseLong(pair.get(0).toString()) / 1_000_000L;
                            String text = String.valueOf(pair.get(1));
                            if (text.length() > MAX_LINE_CHARS) {
                                text = text.substring(0, MAX_LINE_CHARS) + "… (truncated)";
                            }
                            lines.add(new MonitoringResponse.LogLine(millis, level, text));
                        }
                    }
                }
            }
        }
        // Loki returns one stream per label set, each ordered; merging them
        // needs a real sort. Newest first, matching how a log is read when
        // something has just gone wrong.
        lines.sort(Comparator.comparingLong(MonitoringResponse.LogLine::at).reversed());
        boolean truncated = lines.size() > wanted;
        return new MonitoringResponse.LogsResponse(true, null,
                truncated ? lines.subList(0, wanted) : lines, truncated);
    }

    private static String nanos(Instant instant) {
        return Long.toString(instant.getEpochSecond() * 1_000_000_000L + instant.getNano());
    }
}
