package com.oak.dms.search.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.search.SearchIntegrationTestSupport;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.index.IndexAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Engine contract against the disposable OpenSearch node: index, bulk, versions, aliases, delete, health, breaker. */
class OpenSearchRestEngineIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SearchProperties props;
    private OpenSearchRestEngine engine;
    private IndexAdmin admin;

    @BeforeEach
    void setUp() {
        SearchIntegrationTestSupport.assumeAvailable();
        props = SearchIntegrationTestSupport.props();
        engine = new OpenSearchRestEngine(props);
        admin = new IndexAdmin(engine, props);
        admin.ensureIndex();
    }

    @AfterEach
    void tearDown() {
        if (props != null) {
            SearchIntegrationTestSupport.cleanup(props);
        }
    }

    private static String doc(UUID tenant, UUID id, String title, long counter) throws Exception {
        var n = JSON.createObjectNode();
        n.put("tenant_id", tenant.toString());
        n.put("document_id", id.toString());
        n.put("change_counter", counter);
        n.put("title", title);
        n.put("lifecycle_state", "active");
        return JSON.writeValueAsString(n);
    }

    private static JsonNode matchAll() {
        return JSON.createObjectNode().set("match_all", JSON.createObjectNode());
    }

    private long countAll(UUID tenant) {
        engine.refresh(admin.writeAlias());
        return engine.count(admin.readAlias(tenant), tenant.toString(), matchAll());
    }

    @Test
    void healthIsGreenOrYellowOnTheTestNode() {
        assertThat(engine.health()).isIn(SearchEngine.Health.GREEN, SearchEngine.Health.YELLOW);
    }

    @Test
    void ensureIndexIsIdempotentAndCreatesTheWriteAlias() {
        admin.ensureIndex();
        admin.ensureIndex();
        assertThat(admin.isReady()).isTrue();
        assertThat(engine.indicesForAlias(admin.writeAlias())).containsExactly(admin.indexName(1));
    }

    @Test
    void bulkIndexSearchAndTenantAliasIsolation() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        admin.ensureTenantAlias(a);
        admin.ensureTenantAlias(b);
        String index = admin.currentIndex();
        var result = engine.bulk(List.of(
                SearchEngine.BulkOp.index(index, "a1", a.toString(), 1, doc(a, UUID.randomUUID(), "Alpha contract", 1)),
                SearchEngine.BulkOp.index(index, "a2", a.toString(), 1, doc(a, UUID.randomUUID(), "Alpha invoice", 1)),
                SearchEngine.BulkOp.index(index, "b1", b.toString(), 1, doc(b, UUID.randomUUID(), "Beta contract", 1))));
        assertThat(result.allOk()).isTrue();
        assertThat(countAll(a)).isEqualTo(2);
        assertThat(countAll(b)).isEqualTo(1);

        // A crafted query through tenant A's alias can never see tenant B (alias filter), even asking for everything.
        JsonNode hits = engine.search(admin.readAlias(a), a.toString(), JSON.readTree(
                "{\"query\":{\"match_all\":{}},\"size\":50,\"_source\":[\"tenant_id\"]}"));
        for (JsonNode h : hits.path("hits").path("hits")) {
            assertThat(h.path("_source").path("tenant_id").asText()).isEqualTo(a.toString());
        }
        // A crafted routing value for tenant B does not help: the alias's own routing and filter win, so the
        // caller still sees only tenant A's documents and never one of B's.
        JsonNode crossed = engine.search(admin.readAlias(a), b.toString(), JSON.readTree(
                "{\"query\":{\"match_all\":{}},\"size\":50,\"_source\":[\"tenant_id\"]}"));
        assertThat(crossed.path("hits").path("hits")).hasSize(2);
        for (JsonNode h : crossed.path("hits").path("hits")) {
            assertThat(h.path("_source").path("tenant_id").asText()).isEqualTo(a.toString());
        }
    }

    @Test
    void externalVersionNeverLetsAnOlderWriteOverwriteNewerData() throws Exception {
        UUID t = UUID.randomUUID();
        admin.ensureTenantAlias(t);
        String index = admin.currentIndex();
        String id = UUID.randomUUID().toString();
        assertThat(engine.bulk(List.of(SearchEngine.BulkOp.index(index, id, t.toString(), 10, doc(t, UUID.randomUUID(), "new title", 10)))).allOk()).isTrue();
        var late = engine.bulk(List.of(SearchEngine.BulkOp.index(index, id, t.toString(), 5, doc(t, UUID.randomUUID(), "old title", 5))));
        assertThat(late.allOk()).as("a version conflict counts as success").isTrue();
        assertThat(late.items().get(0).versionConflict()).isTrue();
        engine.refresh(index);
        JsonNode r = engine.search(admin.readAlias(t), t.toString(), JSON.readTree("{\"query\":{\"match_all\":{}}}"));
        assertThat(r.path("hits").path("hits").get(0).path("_source").path("title").asText()).isEqualTo("new title");

        // A late delete (older version) must not remove the newer document either.
        var lateDelete = engine.bulk(List.of(SearchEngine.BulkOp.delete(index, id, t.toString(), 3)));
        assertThat(lateDelete.items().get(0).versionConflict()).isTrue();
        assertThat(countAll(t)).isEqualTo(1);
        // A newer delete removes it; deleting something absent is fine.
        assertThat(engine.bulk(List.of(SearchEngine.BulkOp.delete(index, id, t.toString(), 20))).allOk()).isTrue();
        assertThat(engine.bulk(List.of(SearchEngine.BulkOp.delete(index, "never-existed", t.toString(), 20))).allOk()).isTrue();
        assertThat(countAll(t)).isZero();
    }

    @Test
    void strictMappingRejectsUnknownFieldsPerItemWithoutFailingTheBatch() throws Exception {
        UUID t = UUID.randomUUID();
        admin.ensureTenantAlias(t);
        String index = admin.currentIndex();
        var bad = JSON.createObjectNode();
        bad.put("tenant_id", t.toString());
        bad.put("not_in_mapping", "x");
        var result = engine.bulk(List.of(
                SearchEngine.BulkOp.index(index, "bad", t.toString(), 1, JSON.writeValueAsString(bad)),
                SearchEngine.BulkOp.index(index, "good", t.toString(), 1, doc(t, UUID.randomUUID(), "fine", 1))));
        assertThat(result.items().get(0).ok()).isFalse();
        assertThat(result.items().get(0).error()).isEqualTo("strict_dynamic_mapping_exception");
        assertThat(result.items().get(1).ok()).isTrue();
    }

    @Test
    void deleteByQueryRemovesOnlyMatchingDocuments() throws Exception {
        UUID t = UUID.randomUUID();
        admin.ensureTenantAlias(t);
        String index = admin.currentIndex();
        engine.bulk(List.of(
                SearchEngine.BulkOp.index(index, "x1", t.toString(), 1, doc(t, UUID.randomUUID(), "keep", 1)),
                SearchEngine.BulkOp.index(index, "x2", t.toString(), 1, doc(t, UUID.randomUUID(), "drop", 1))));
        engine.refresh(index);
        long deleted = engine.deleteByQuery(admin.readAlias(t), t.toString(),
                JSON.readTree("{\"match\":{\"title\":\"drop\"}}"));
        assertThat(deleted).isEqualTo(1);
        assertThat(countAll(t)).isEqualTo(1);
    }

    @Test
    void unreachableEngineReportsDownThrowsAndOpensTheBreaker() {
        SearchProperties dead = SearchIntegrationTestSupport.props();
        dead.getOpensearch().setUrl("http://localhost:1");
        dead.getOpensearch().setConnectTimeout(java.time.Duration.ofMillis(300));
        dead.getQuery().setBreakerFailures(2);
        OpenSearchRestEngine broken = new OpenSearchRestEngine(dead);
        assertThat(broken.health()).isEqualTo(SearchEngine.Health.DOWN);
        assertThatThrownBy(() -> broken.indexExists("x")).isInstanceOf(SearchEngineException.class);
        // two failures so far: the circuit is now open and calls fail immediately
        long start = System.nanoTime();
        assertThatThrownBy(() -> broken.indexExists("x")).hasMessageContaining("circuit open");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(100);
    }
}
