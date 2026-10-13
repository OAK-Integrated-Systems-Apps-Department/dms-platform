package com.oak.dms.monitoring;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * System metrics for the monitoring screen, read from Prometheus.
 * <p>
 * This exists so the screen can be a page of the DMS rather than an iframe
 * of Grafana. The practical difference is authentication: Grafana has its
 * own auth, and embedding it meant running it with anonymous Viewer access
 * so the frame would render - which made every metric readable by anyone who
 * could reach Grafana's port, whatever the DMS thought about them. Served
 * from here, the same figures sit behind the same PLATFORM_ADMIN check as
 * every other cross-tenant screen, and neither Prometheus nor Grafana needs
 * to be reachable from a browser at all.
 * <p>
 * Works WITHOUT tenant context, like the rest of {@code /api/v1/platform} -
 * a platform administrator belongs to no tenant. {@code hasRole} rather than
 * a permission code for the same reason: the permission catalogue is
 * tenant-scoped, and this data spans every tenant and the host besides.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
@Tag(name = "Monitoring", description = "System metrics for platform administrators")
public class MonitoringController {

    /**
     * The windows the screen offers. Named rather than free-form: a caller
     * who could pass any duration could ask for a year at a fine step, and
     * the range query behind it has no cost ceiling.
     */
    private static final Map<String, Duration> WINDOWS = Map.of(
            "1h", Duration.ofHours(1),
            "6h", Duration.ofHours(6),
            "24h", Duration.ofHours(24),
            "7d", Duration.ofDays(7));

    private final MonitoringService monitoringService;
    private final DependencyHealthService dependencyHealthService;
    private final LogsService logsService;
    private final AlertsService alertsService;

    @GetMapping("/snapshot")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Current value of every monitored figure",
            description = "Counts, rates and sizes from Prometheus. A figure with a null value has no "
                    + "sample - which is not the same as zero, and the screen shows it as 'no data'.")
    public MonitoringResponse.Snapshot snapshot() {
        return monitoringService.snapshot();
    }

    @GetMapping("/series")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "The chartable figures over a window",
            description = "range is one of 1h, 6h, 24h, 7d. The step is derived from the window, so "
                    + "every window costs about the same to compute and to draw.")
    public MonitoringResponse.SeriesResponse series(
            @RequestParam(defaultValue = "6h") String range) {
        // An unknown range falls back rather than 400ing: this drives a
        // dropdown, and a bad value there is a bug in our own UI, not
        // something worth failing a dashboard over.
        return monitoringService.series(WINDOWS.getOrDefault(range, Duration.ofHours(6)));
    }

    @GetMapping("/services")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Which services are answering, and which are not",
            description = "Scraped services report via `up`; the ones with no metrics endpoint "
                    + "(Keycloak, object storage, the converter, the virus scanner, mail, the web "
                    + "app) are reachability-probed instead. Anything down sorts first.")
    public MonitoringResponse.ServicesResponse services() {
        return monitoringService.services();
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Every alert rule and whether it is firing",
            description = "Read from Prometheus. There is no Alertmanager, so nothing else delivers "
                    + "these - this endpoint is how a firing alert is seen at all. Firing and "
                    + "broken rules sort first.")
    public MonitoringResponse.AlertsResponse alerts() {
        return alertsService.alerts();
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Recent log lines for one service, newest first",
            description = "Read from Loki. level is one of TRACE..FATAL, range one of 15m, 1h, 6h, "
                    + "24h, 7d. Log lines can carry anything the service printed - this is "
                    + "PLATFORM_ADMIN only for that reason, and the search text is escaped into "
                    + "the query, never run as query syntax.")
    public MonitoringResponse.LogsResponse logs(
            @RequestParam String service,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1h") String range,
            @RequestParam(required = false) Integer limit) {
        return logsService.logs(service, level, search, range, limit);
    }

    @GetMapping("/health")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "What the backend itself thinks of its dependencies",
            description = "Spring Actuator's health indicators, read in-process. Different from "
                    + "/services: that says a port is listening, this says a query from our own "
                    + "pool actually came back. They disagree exactly when it matters.")
    public MonitoringResponse.DependencyHealth health() {
        return dependencyHealthService.health();
    }

    /**
     * Says yes, and who, for the panel proxy.
     * <p>
     * The monitoring screen embeds Grafana panels, and an iframe cannot be
     * asked for a second login. The old answer to that was to run Grafana
     * with anonymous access, which made every metric readable by anyone who
     * could reach its port. The new answer is Grafana's auth proxy: the DMS
     * decides who the caller is, and Grafana trusts the name it is handed.
     * <p>
     * This endpoint is the "decides" half. The frontend's panel route calls
     * it with the caller's own session, gets a username back, and forwards
     * that to Grafana as {@code X-WEBAUTH-USER}. A caller who is not a
     * platform administrator gets 401/403 here and the proxy never reaches
     * Grafana at all.
     * <p>
     * The name is returned in a header rather than a body because the only
     * consumer copies it straight into another header.
     */
    @GetMapping("/panel-auth")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Confirms the caller may view monitoring panels, and names them",
            description = "For the frontend's Grafana panel proxy. Returns 204 with X-Dms-User; "
                    + "never a body.")
    public ResponseEntity<Void> panelAuth(@AuthenticationPrincipal Jwt jwt) {
        // preferred_username, matching every other place this codebase names
        // the caller. Falls back to the subject so a token without the claim
        // still proxies rather than failing shut on a cosmetic detail.
        String username = jwt.getClaimAsString("preferred_username");
        return ResponseEntity.noContent()
                .header("X-Dms-User", username != null && !username.isBlank() ? username : jwt.getSubject())
                .build();
    }
}
