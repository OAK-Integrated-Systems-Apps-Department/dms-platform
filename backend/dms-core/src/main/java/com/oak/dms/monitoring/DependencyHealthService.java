package com.oak.dms.monitoring;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What the backend itself thinks of its dependencies, from Actuator.
 * <p>
 * This answers a different question from the Services board, and the
 * difference is the reason it exists. A reachability probe says "something
 * is listening on that port". Actuator's database indicator says "a
 * connection from OUR pool, with OUR credentials, ran a query and came
 * back". Those come apart exactly when it matters: a Postgres that is up
 * but refusing this app's password, a pool with no free connections, a disk
 * with no room left. The container is green the whole time.
 * <p>
 * Read in-process from {@link HealthEndpoint} rather than over HTTP from
 * {@code /actuator/health}. Nothing has to be exposed, no second
 * authentication happens, and the figures cannot disagree with what the
 * container's own health check sees, because it is the same object.
 */
@Service
@RequiredArgsConstructor
public class DependencyHealthService {

    private final HealthEndpoint healthEndpoint;

    public MonitoringResponse.DependencyHealth health() {
        HealthDescriptor root = healthEndpoint.health();
        List<MonitoringResponse.Dependency> dependencies = new ArrayList<>();

        if (root instanceof CompositeHealthDescriptor composite) {
            for (Map.Entry<String, HealthDescriptor> entry : composite.getComponents().entrySet()) {
                dependencies.add(describe(entry.getKey(), entry.getValue()));
            }
        }

        // Anything not UP first, for the same reason the Services board does
        // it: this is read to find what is broken.
        dependencies.sort(Comparator
                .comparing((MonitoringResponse.Dependency d) -> "UP".equals(d.status()))
                .thenComparing(MonitoringResponse.Dependency::label));

        return new MonitoringResponse.DependencyHealth(root.getStatus().getCode(), dependencies);
    }

    private MonitoringResponse.Dependency describe(String name, HealthDescriptor descriptor) {
        String detail = null;
        if (descriptor instanceof IndicatedHealthDescriptor indicated) {
            detail = summarise(name, indicated.getDetails());
        }
        return new MonitoringResponse.Dependency(name, label(name), descriptor.getStatus().getCode(), detail);
    }

    /**
     * One short line, or nothing at all.
     * <p>
     * These two cases ARE the allow-list. Health details are written by
     * whoever wrote the indicator and are not curated for an audience -
     * they can carry connection strings, validation queries and version
     * numbers that say more about how to attack this system than about
     * whether it is working. Rather than filter a map of unknown keys,
     * nothing is shown unless it is named here, so a new indicator cannot
     * quietly start publishing something.
     * <p>
     * Disk space earns its line because the numbers mean something at a
     * glance: "4.2 GB free of 500 GB" is a warning you can act on before it
     * becomes an outage. An error message earns its line because it is the
     * answer to "why is this down", which is the only question being asked
     * at that point.
     */
    private String summarise(String name, Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        if ("diskSpace".equals(name)
                && details.get("free") instanceof Number free
                && details.get("total") instanceof Number total
                && total.longValue() > 0) {
            return "%s free of %s".formatted(bytes(free.longValue()), bytes(total.longValue()));
        }
        if (details.get("error") instanceof String error) {
            return error.length() > 180 ? error.substring(0, 180) + "…" : error;
        }
        return null;
    }

    private static String bytes(long value) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double size = value;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return size < 10 && unit > 0
                ? "%.1f %s".formatted(size, units[unit])
                : "%.0f %s".formatted(size, units[unit]);
    }

    /** Actuator's indicator names are terse and lower-case; these are for reading. */
    private static String label(String name) {
        return switch (name) {
            case "db" -> "Database";
            case "diskSpace" -> "Disk space";
            case "ping" -> "Application";
            case "redis" -> "Redis";
            case "rabbit" -> "RabbitMQ";
            case "mail" -> "Mail";
            case "ssl" -> "TLS certificates";
            case "livenessState" -> "Liveness";
            case "readinessState" -> "Readiness";
            default -> name;
        };
    }
}
