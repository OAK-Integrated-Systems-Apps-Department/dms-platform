package com.oak.dms.search.index;

import com.oak.dms.search.SearchIntegrationTestSupport;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backfill, tenant rebuild (generation sweep) and global rebuild (new index + atomic alias swap) with no downtime, and
 * the daily reconciler that catches anything the triggers missed.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchRebuildIT {

    private static final String PREFIX = "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @DynamicPropertySource
    static void search(DynamicPropertyRegistry registry) {
        registry.add("dms.search.opensearch.url", SearchIntegrationTestSupport::url);
        registry.add("dms.search.index.prefix", () -> PREFIX);
    }

    @MockitoBean(name = "renditionGenerationListener")
    private Object backgroundRenditions;

    @Autowired
    private SearchEngine engine;
    @Autowired
    private IndexAdmin admin;
    @Autowired
    private SearchIndexer indexer;
    @Autowired
    private SearchBackfillService backfill;
    @Autowired
    private SearchRebuildService rebuild;
    @Autowired
    private SearchReconciler reconciler;
    @Autowired
    private SearchTenantStateRepository tenantState;
    @Autowired
    private SearchProperties props;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WorkflowFixtures fx;

    private SearchSyncSupport s;
    private UUID tenant;
    private User owner;
    private UUID type;

    @BeforeEach
    void setUp() {
        SearchIntegrationTestSupport.assumeAvailable();
        s = new SearchSyncSupport(engine, admin, indexer, jdbc, fx);
        tenant = fx.tenant();
        owner = fx.user(tenant, "Rebuilder");
        type = fx.documentType(tenant, "Report");
        jdbc.update("delete from search_index_jobs");
        admin.forgetTenantAliases();
    }

    @AfterAll
    static void cleanUp() {
        SearchProperties p = new SearchProperties();
        p.getIndex().setPrefix(PREFIX);
        SearchIntegrationTestSupport.cleanup(p);
    }

    private List<UUID> documents(UUID forTenant, User forOwner, UUID forType, int n, String prefix) {
        List<UUID> docs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            docs.add(s.textDocument(forTenant, forType, forOwner, prefix + " " + i, prefix + i + ".txt", "words about " + prefix + i));
        }
        return docs;
    }

    // ------------------------------------------------------------------ backfill

    @Test
    void backfillIndexesPreExistingDocumentsAndMakesTheTenantReady() {
        List<UUID> docs = documents(tenant, owner, type, 6, "Legacy");
        jdbc.update("delete from search_index_jobs"); // as if these documents predate the index
        assertThat(tenantState.find(tenant)).isEmpty();

        assertThat(backfill.run(tenant, "backfill")).isEqualTo(6);
        assertThat(tenantState.find(tenant).orElseThrow().indexReady()).as("not ready while jobs are queued").isFalse();
        s.drain();

        var state = tenantState.find(tenant).orElseThrow();
        assertThat(state.backfillState()).isEqualTo("done");
        assertThat(state.indexReady()).isTrue();
        assertThat(s.countInIndex(tenant)).isEqualTo(6);
        docs.forEach(d -> assertThat(s.indexed(tenant, d)).isTrue());
    }

    @Test
    void anInterruptedBackfillResumesFromItsCursor() {
        List<UUID> docs = documents(tenant, owner, type, 6, "Resumable");
        jdbc.update("delete from search_index_jobs");
        // PostgreSQL orders uuids bytewise (unsigned); java.util.UUID.compareTo is signed, so order by the hex text
        List<UUID> sorted = docs.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        tenantState.ensure(tenant);
        tenantState.setBackfill(tenant, "running", sorted.get(2)); // the first three were already queued before the interruption

        assertThat(backfill.run(tenant, "backfill")).as("only the documents after the cursor").isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from search_index_jobs where tenant_id = ?", Long.class, tenant)).isEqualTo(3);
    }

    @Test
    void backfillSkipsDeletedPurgedAndTemplateDocuments() {
        List<UUID> docs = documents(tenant, owner, type, 4, "Mixed");
        jdbc.update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", docs.get(0));
        jdbc.update("update documents set purged_at = now(), is_deleted = true, deleted_at = now() where document_id = ?", docs.get(1));
        jdbc.update("update documents set is_template = true where document_id = ?", docs.get(2));
        jdbc.update("delete from search_index_jobs");
        assertThat(backfill.run(tenant, "backfill")).isEqualTo(1);
        s.drain();
        assertThat(s.countInIndex(tenant)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ tenant rebuild

    @Test
    void aTenantRebuildBumpsTheGenerationKeepsSearchWorkingAndSweepsStaleDocuments() {
        List<UUID> docs = documents(tenant, owner, type, 4, "Gen");
        backfill.run(tenant, "backfill");
        s.drain();
        assertThat(tenantState.find(tenant).orElseThrow().indexReady()).isTrue();
        long g1 = tenantState.generation(tenant);
        assertThat(s.source(tenant, docs.get(0)).path("generation").asLong()).isEqualTo(g1);

        // a stale entry for a document that no longer exists (e.g. a missed delete)
        UUID ghost = UUID.randomUUID();
        engine.bulk(List.of(SearchEngine.BulkOp.index(admin.currentIndex(), ghost.toString(), tenant.toString(), 1,
                "{\"tenant_id\":\"" + tenant + "\",\"document_id\":\"" + ghost + "\",\"title\":\"Ghost\",\"generation\":" + g1 + "}")));
        assertThat(s.countInIndex(tenant)).isEqualTo(5);

        long g2 = tenantState.startRebuild(tenant);
        assertThat(g2).isEqualTo(g1 + 1);
        assertThat(tenantState.find(tenant).orElseThrow().indexReady()).as("the tenant keeps serving search during a rebuild").isTrue();
        assertThat(s.countInIndex(tenant)).as("nothing is removed up front").isEqualTo(5);

        assertThat(rebuild.finishTenantRebuild(tenant, g2, Duration.ofMinutes(2))).isTrue();

        assertThat(s.countInIndex(tenant)).isEqualTo(4);
        assertThat(s.indexed(tenant, ghost)).as("the stale document was swept").isFalse();
        docs.forEach(d -> assertThat(s.source(tenant, d).path("generation").asLong()).isEqualTo(g2));
    }

    @Test
    void aTenantRebuildDoesNotSweepWhileJobsHaveFailed() {
        List<UUID> docs = documents(tenant, owner, type, 2, "Safe");
        backfill.run(tenant, "backfill");
        s.drain();
        UUID ghost = UUID.randomUUID();
        engine.bulk(List.of(SearchEngine.BulkOp.index(admin.currentIndex(), ghost.toString(), tenant.toString(), 1,
                "{\"tenant_id\":\"" + tenant + "\",\"document_id\":\"" + ghost + "\",\"title\":\"Ghost\",\"generation\":1}")));

        long g2 = tenantState.startRebuild(tenant);
        // one job is parked as failed: a sweep now could delete documents that simply were not re-indexed
        jdbc.update("insert into search_index_jobs (tenant_id, kind, document_id, reason, change_counter, status, attempts, max_attempts)"
                + " values (?, 'document', ?, 'x', 1, 'failed', 8, 8)", tenant, UUID.randomUUID());
        assertThat(rebuild.finishTenantRebuild(tenant, g2, Duration.ofMinutes(2))).isFalse();
        assertThat(s.indexed(tenant, ghost)).as("no sweep happened").isTrue();
        docs.forEach(d -> assertThat(s.indexed(tenant, d)).isTrue());
    }

    // ------------------------------------------------------------------ global rebuild

    @Test
    void aGlobalRebuildSwapsToANewIndexWithNoFailedQueriesAndNoLostChanges() throws Exception {
        UUID tenantB = fx.tenant();
        User ownerB = fx.user(tenantB, "OtherOrg");
        UUID typeB = fx.documentType(tenantB, "Memo");
        List<UUID> a = documents(tenant, owner, type, 12, "Alpha");
        List<UUID> b = documents(tenantB, ownerB, typeB, 8, "Beta");
        backfill.run(tenant, "backfill");
        backfill.run(tenantB, "backfill");
        s.drain();
        String oldIndex = admin.currentIndex();
        assertThat(s.countInIndex(tenant)).isEqualTo(12);

        // queries run continuously while the rebuild and swap happen
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger queries = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            var q = SearchSyncSupport.parse("{\"match_all\":{}}");
            while (!done.get()) {
                try {
                    long n = engine.count(admin.readAlias(tenant), tenant.toString(), q);
                    if (n == 0) {
                        throw new AssertionError("tenant A looked empty during the rebuild");
                    }
                    queries.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                }
            }
        });

        // a live change lands while the new index is loading: it must reach both indexes
        CompletableFuture<SearchRebuildService.GlobalResult> rebuilding = CompletableFuture.supplyAsync(
                () -> rebuild.rebuildAll(Duration.ofMinutes(3), true));
        Thread.sleep(150);
        jdbc.update("update documents set title = 'Alpha renamed during rebuild' where document_id = ?", a.get(0));
        var result = rebuilding.get(4, java.util.concurrent.TimeUnit.MINUTES);
        s.drain();
        done.set(true);
        reader.get();

        assertThat(failures).as("no query failed or saw an empty index across the swap").isEmpty();
        assertThat(queries.get()).isGreaterThan(5);
        String newIndex = admin.currentIndex();
        assertThat(newIndex).isNotEqualTo(oldIndex).isEqualTo(result.newIndex());
        assertThat(engine.indexExists(oldIndex)).as("the old index was dropped").isFalse();
        assertThat(engine.indicesForAlias(admin.rebuildingAlias())).as("the rebuilding marker is gone").isEmpty();
        assertThat(engine.indicesForAlias(admin.readAlias(tenant))).containsExactly(newIndex);
        assertThat(engine.indicesForAlias(admin.readAlias(tenantB))).containsExactly(newIndex);
        assertThat(s.countInIndex(tenant)).isEqualTo(12);
        assertThat(s.countInIndex(tenantB)).isEqualTo(8);
        assertThat(s.source(tenant, a.get(0)).path("title").asText()).isEqualTo("Alpha renamed during rebuild");

        // and new changes now go to the new index only
        jdbc.update("update documents set title = 'After swap' where document_id = ?", b.get(0));
        s.drain();
        assertThat(s.source(tenantB, b.get(0)).path("title").asText()).isEqualTo("After swap");
    }

    @Test
    void aFailedGlobalRebuildLeavesTheCurrentIndexAndAliasesUntouched() {
        documents(tenant, owner, type, 3, "Stable");
        backfill.run(tenant, "backfill");
        s.drain();
        String current = admin.currentIndex();
        // a parked job makes the rebuild refuse to swap
        jdbc.update("insert into search_index_jobs (tenant_id, kind, document_id, reason, change_counter, status, attempts, max_attempts)"
                + " values (?, 'document', ?, 'x', 1, 'failed', 8, 8)", tenant, UUID.randomUUID());

        assertThatThrownBy(() -> rebuild.rebuildAll(Duration.ofSeconds(30), true)).isInstanceOf(IllegalStateException.class);

        assertThat(admin.currentIndex()).isEqualTo(current);
        assertThat(engine.indicesForAlias(admin.rebuildingAlias())).isEmpty();
        assertThat(engine.indexExists(admin.indexName(2))).as("the half-built target was removed").isFalse();
        assertThat(s.countInIndex(tenant)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ reconciler

    @Test
    void theReconcilerFindsMissingAndOrphanDocumentsQueuesRepairsAndReportsTheGap() {
        List<UUID> docs = documents(tenant, owner, type, 5, "Recon");
        backfill.run(tenant, "backfill");
        s.drain();
        assertThat(reconciler.reconcile(tenant)).isEqualTo(new SearchReconciler.Gap(0, 0));

        // one indexed document silently disappears from the index; one orphan appears
        UUID orphan = UUID.randomUUID();
        engine.bulk(List.of(SearchEngine.BulkOp.index(admin.currentIndex(), orphan.toString(), tenant.toString(), 1,
                "{\"tenant_id\":\"" + tenant + "\",\"document_id\":\"" + orphan + "\",\"title\":\"Orphan\"}")));
        engine.deleteByQuery(admin.readAlias(tenant), tenant.toString(), SearchSyncSupport.parse("{\"ids\":{\"values\":[\"" + docs.get(1) + "\"]}}"));
        assertThat(s.indexed(tenant, docs.get(1))).isFalse();

        SearchReconciler.Gap gap = reconciler.reconcile(tenant);
        assertThat(gap).isEqualTo(new SearchReconciler.Gap(1, 1));
        var state = tenantState.find(tenant).orElseThrow();
        assertThat(state.lastReconciledAt()).isNotNull();
        assertThat(state.lastGapJson()).contains("\"missing\": 1").contains("\"orphans\": 1");

        s.drain();
        assertThat(s.indexed(tenant, docs.get(1))).as("the missing document was restored").isTrue();
        assertThat(s.indexed(tenant, orphan)).as("the orphan was removed").isFalse();
        assertThat(reconciler.reconcile(tenant)).isEqualTo(new SearchReconciler.Gap(0, 0));
    }

    @Test
    void theReconcilerSkipsTenantsThatAreNotReadyOrAreSwitchedOff() {
        documents(tenant, owner, type, 2, "Unready");
        assertThat(reconciler.reconcile(tenant)).as("no state yet").isNull();
        tenantState.ensure(tenant);
        assertThat(reconciler.reconcile(tenant)).as("not ready").isNull();
        tenantState.setIndexReady(tenant, true);
        tenantState.setEnabled(tenant, false);
        assertThat(reconciler.reconcile(tenant)).as("switched off").isNull();
    }
}
