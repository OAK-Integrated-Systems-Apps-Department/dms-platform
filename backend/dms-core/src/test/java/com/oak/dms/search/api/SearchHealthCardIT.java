package com.oak.dms.search.api;

import com.oak.dms.monitoring.DependencyHealthService;
import com.oak.dms.monitoring.MonitoringResponse;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** OpenSearch is on the system dashboard's integration list, with a readable label and a one-word detail. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchHealthCardIT extends SearchApiSupport {

    @Autowired
    private DependencyHealthService health;

    @Test
    void theSearchEngineAppearsWithALabelAndAStatus() {
        MonitoringResponse.Dependency search = health.health().dependencies().stream()
                .filter(d -> "openSearch".equals(d.id())).findFirst().orElseThrow();
        assertThat(search.label()).isEqualTo("Search (OpenSearch)");
        assertThat(search.status()).isEqualTo("UP");
        assertThat(search.detail()).startsWith("Cluster ");
        assertThat(search.detail()).doesNotContain("http");
    }
}
