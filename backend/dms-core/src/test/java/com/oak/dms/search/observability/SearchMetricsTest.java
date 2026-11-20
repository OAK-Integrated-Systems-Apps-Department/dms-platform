package com.oak.dms.search.observability;

import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.index.SearchJobRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every search meter has closed tags, none identifies a tenant or person, and every series exists before its first event. */
class SearchMetricsTest {

    private final SearchEngine engine = mock(SearchEngine.class);
    private final SearchJobRepository jobs = mock(SearchJobRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private SearchMetrics metrics() {
        when(jobs.oldestDueAt()).thenReturn(Optional.empty());
        when(engine.health()).thenReturn(SearchEngine.Health.GREEN);
        return new SearchMetrics(registry, jobs, engine);
    }

    @Test
    void noMeterCarriesATenantUserQueryOrDocumentTag_andEveryTagValueIsFromAClosedSet() {
        SearchMetrics m = metrics();
        m.queryTimer("search", "ok");
        m.fallback("anything-unexpected");
        m.jobsProcessed("surprise", "odd", 1);
        m.bulkFailures("weird", 1);
        m.previewFailure("not-a-reason");

        Set<String> allowedKeys = Set.of("mode", "outcome", "reason", "kind");
        Set<String> allowedValues = Set.of("search", "basic", "ok", "capped", "error", "disabled", "not_ready", "continuing",
                "engine", "document", "folder_subtree", "tenant_purge", "indexed", "deleted", "done", "failed", "item",
                "request", "unsupported", "timeout", "policy_blocked");
        long searchMeters = registry.getMeters().stream().filter(x -> x.getId().getName().startsWith("dms.search.")).count();
        assertThat(searchMeters).isGreaterThan(20);
        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith("dms.search.")) {
                continue;
            }
            meter.getId().getTags().forEach(t -> {
                assertThat(t.getKey()).as(meter.getId().getName()).isIn(allowedKeys);
                assertThat(t.getValue()).as(meter.getId().getName() + "." + t.getKey()).isIn(allowedValues);
            });
        }
        // unexpected values were folded into the closed sets, not turned into new series
        assertThat(registry.find("dms.search.fallback").counters()).hasSize(4);
        assertThat(registry.find("dms.search.preview.failures").counters()).hasSize(4);
    }

    @Test
    void everySeriesIsRegisteredAtStartupSoADashboardReadsZero() {
        metrics();
        for (String name : new String[]{"dms.search.index.lag.seconds", "dms.search.jobs.pending", "dms.search.jobs.failed",
                "dms.search.breaker.state", "dms.search.cluster.status", "dms.search.index.bulk", "dms.search.verify.dropped",
                "dms.search.query.verify.rounds"}) {
            assertThat(registry.find(name).meters()).as(name).isNotEmpty();
        }
        assertThat(registry.find("dms.search.query").meters()).hasSize(6);
        assertThat(registry.find("dms.search.jobs.processed").meters()).hasSize(12);
        assertThat(registry.find("dms.search.bulk.failures").counters()).hasSize(2);
        assertThat(registry.find("dms.search.fallback").counters()).allSatisfy(c -> assertThat(c.count()).isZero());
    }

    @Test
    void theClusterAndBreakerGaugesFollowTheEngine() {
        when(engine.breakerState()).thenReturn(0);
        SearchMetrics m = metrics();
        assertThat(registry.get("dms.search.cluster.status").gauge().value()).isEqualTo(0);
        assertThat(registry.get("dms.search.breaker.state").gauge().value()).isEqualTo(0);

        // a stopped node: the next read (after the 10 s cache) says unreachable, and an open breaker says 1
        when(engine.breakerState()).thenReturn(1);
        assertThat(registry.get("dms.search.breaker.state").gauge().value()).isEqualTo(1);
        when(engine.health()).thenReturn(SearchEngine.Health.DOWN);
        ReflectionTestUtils.setField(m, "clusterCachedAt", 0L);
        assertThat(registry.get("dms.search.cluster.status").gauge().value()).isEqualTo(3);
        when(engine.health()).thenReturn(SearchEngine.Health.RED);
        ReflectionTestUtils.setField(m, "clusterCachedAt", 0L);
        assertThat(registry.get("dms.search.cluster.status").gauge().value()).isEqualTo(2);
    }

    @Test
    void bulkFailuresAreCountedByKind() {
        SearchMetrics m = metrics();
        m.bulkFailures("item", 3);
        m.bulkFailures("request", 1);
        assertThat(registry.get("dms.search.bulk.failures").tag("kind", "item").counter().count()).isEqualTo(3);
        assertThat(registry.get("dms.search.bulk.failures").tag("kind", "request").counter().count()).isEqualTo(1);
    }
}
