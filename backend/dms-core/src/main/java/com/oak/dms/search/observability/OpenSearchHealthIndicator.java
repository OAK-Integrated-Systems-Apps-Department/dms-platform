package com.oak.dms.search.observability;

import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Shows the search engine on the system dashboard's integration-health list
 * ({@code DependencyHealthService} picks up every indicator automatically).
 *
 * <p>Excluded from the {@code core} health group (application.yml), so a stopped OpenSearch
 * shows DOWN here and on the dashboard but never fails the container probe: search degrades
 * to the title search, it is not "the backend is broken". Yellow is normal on a one-node
 * cluster (replicas can't be placed) and reports UP; red (a primary shard missing) is DOWN.
 */
@Component("openSearch")
public class OpenSearchHealthIndicator implements HealthIndicator {

    private final SearchEngine engine;
    private final SearchProperties props;

    public OpenSearchHealthIndicator(SearchEngine engine, SearchProperties props) {
        this.engine = engine;
        this.props = props;
    }

    @Override
    public Health health() {
        if (!props.isEnabled()) {
            return Health.up().withDetail("search", "disabled").build();
        }
        SearchEngine.Health h = engine.health();
        return switch (h) {
            case GREEN, YELLOW -> Health.up().withDetail("cluster", h.name().toLowerCase()).build();
            case RED -> Health.down().withDetail("cluster", "red").build();
            case DOWN -> Health.down().withDetail("error", "search engine unreachable; title search is in use").build();
        };
    }
}
