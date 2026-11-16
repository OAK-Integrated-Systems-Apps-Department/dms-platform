package com.oak.dms.search.api;

import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.observability.SearchMetrics;
import com.oak.dms.security.auth.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets the result preview panel say that it fell back (the one thing only the browser can see). A counter only:
 * the body is a closed enum, nothing about the user, the document or the query is stored or logged, a login is
 * required, and each person may make about 30 calls a minute (more answer 429 and are ignored). It is
 * fire-and-forget on the client; the UI never waits for it.
 */
@RestController
@RequestMapping("/api/v1/search/telemetry")
@Tag(name = "Search telemetry")
public class SearchTelemetryController {

    private static final long WINDOW_MS = 60_000;
    private static final int MAX_TRACKED_USERS = 10_000;

    public record PreviewBody(String reason) {
    }

    private final SearchMetrics metrics;
    private final SearchProperties props;
    private final Map<UUID, Deque<Long>> calls = new ConcurrentHashMap<>();

    public SearchTelemetryController(SearchMetrics metrics, SearchProperties props) {
        this.metrics = metrics;
        this.props = props;
    }

    @PostMapping("/preview")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DSR_SEARCH_DOCUMENTS)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Count a preview fallback from the results page (closed reason list; about 30 calls a minute per user)")
    public void preview(@RequestBody PreviewBody body) {
        if (body == null || body.reason() == null || !SearchMetrics.PREVIEW_REASONS.contains(body.reason())) {
            throw new SearchApiException(HttpStatus.BAD_REQUEST, "TELEMETRY_INVALID",
                    "reason must be one of " + SearchMetrics.PREVIEW_REASONS + ".");
        }
        if (!allow(TenantContext.getUserId())) {
            throw new SearchApiException(HttpStatus.TOO_MANY_REQUESTS, "TELEMETRY_RATE_LIMITED", "Too many reports.");
        }
        metrics.previewFailure(body.reason());
    }

    /** A sliding one-minute window per user, in memory (per instance: "about" 30, which is all this needs). */
    private boolean allow(UUID userId) {
        long now = System.currentTimeMillis();
        if (calls.size() > MAX_TRACKED_USERS) {
            calls.values().removeIf(d -> {
                synchronized (d) {
                    return d.isEmpty() || now - d.peekLast() > WINDOW_MS;
                }
            });
        }
        Deque<Long> window = calls.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= props.getAdmin().getTelemetryPerMinute()) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
