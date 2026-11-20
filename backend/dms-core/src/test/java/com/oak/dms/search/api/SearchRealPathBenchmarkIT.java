package com.oak.dms.search.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.search.extract.ExtractedText;
import com.oak.dms.search.extract.TextExtractionService;
import com.oak.dms.search.index.SearchJobRepository;
import com.oak.dms.search.index.SearchRebuildService;
import com.oak.dms.search.index.SearchReconciler;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The REAL-PATH benchmark (S16), opt-in and never part of a normal run ({@code @Tag("perf")}). Where {@link SearchScaleIT}
 * measures querying against documents streamed straight into OpenSearch, this one goes the way production does: documents
 * are written to PostgreSQL <b>with the real V138 triggers enabled</b>, the triggers fill the real queue, and the real
 * {@code SearchIndexer} claims, builds, bulk-writes and completes. Only the text source is stubbed (a fixed body per
 * document), so PDFBox and object storage are out of the measurement: it measures queue + indexer + engine.
 *
 * <p>Run it with {@code -Dsearch.realpath.run=true} (or {@code SEARCH_REALPATH_RUN=true}); without it the test is skipped.
 * Size: {@code -Dsearch.realpath.documents=N} or {@code SEARCH_REALPATH_DOCUMENTS=N}, default 100,000. Anything above
 * 100,000 additionally needs {@code -Dsearch.realpath.confirm-above=true}: the plan says to ask before going higher.
 * Disk guard: refuses to start below 15 GB free on the Docker disk ({@code SEARCH_BENCH_DOCKER_DISK}, default
 * /Volumes/ChamathSSD when it exists) or 5 GB on /, and says so.
 *
 * <p>Measures and reports (printed and appended to {@code target/search-realpath-benchmark.txt}): trigger load, queue drain
 * time and throughput, lag, failed jobs, a tenant backfill (generation sweep), a global rebuild with the alias swap while a
 * query thread runs (failed queries must be 0), the reconciler's gap (must be 0), and the memory of the node and this JVM.
 */
@Tag("perf")
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchRealPathBenchmarkIT extends SearchApiSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT = 100_000;
    private static final int N = Integer.getInteger("search.realpath.documents",
            System.getenv("SEARCH_REALPATH_DOCUMENTS") == null ? DEFAULT : Integer.parseInt(System.getenv("SEARCH_REALPATH_DOCUMENTS")));
    private static final String BODY = "agreement invoice report budget contract proposal quarterly annual supplier customer policy "
            + "audit forecast revenue schedule minutes review summary statement order delivery payment tender approval "
            + "compliance strategy project payroll insurance warranty license lease tax expense asset risk incident training";

    @MockitoBean
    private TextExtractionService extraction;
    @Autowired
    private SearchRebuildService rebuild;
    @Autowired
    private SearchReconciler reconciler;
    @Autowired
    private SearchJobRepository jobs;

    private final List<String> report = new ArrayList<>();

    private void say(String line) {
        report.add(line);
        System.out.println("[search-realpath] " + line);
    }

    @Test
    void theRealIndexingPathAtScale() throws Exception {
        // Opt-in twice: the perf tag AND an explicit switch, because CI runs `*IT` through surefire, which ignores the tag.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("search.realpath.run") || "true".equalsIgnoreCase(System.getenv("SEARCH_REALPATH_RUN")),
                "real-path benchmark not requested (-Dsearch.realpath.run=true or SEARCH_REALPATH_RUN=true)");
        if (N > DEFAULT) {
            assertThat(Boolean.getBoolean("search.realpath.confirm-above"))
                    .as("sizes above %,d need -Dsearch.realpath.confirm-above=true (ask first)", DEFAULT).isTrue();
        }
        diskGuard();
        when(extraction.extract(any())).thenAnswer(inv -> ExtractedText.ready("stub", BODY));
        say("documents: " + N + "  (real triggers, queue and indexer; stubbed text source; 1 shard, 0 replicas, 512 MB heap, one node)");
        UUID seed = fx.document(tenantId, reportType, alice, "Seed document");
        UUID versionRef = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, seed);
        fx.jdbc().update("delete from search_index_jobs");
        try {
            run(seed, versionRef);
        } finally {
            fx.jdbc().execute("alter table documents disable trigger trg_search_documents_ins");
            fx.jdbc().execute("alter table documents disable trigger trg_search_documents_upd");
            try {
                fx.jdbc().update("delete from search_index_jobs where tenant_id = ?", tenantId);
                fx.jdbc().update("delete from documents where tenant_id = ? and document_id <> ?", tenantId, seed);
            } finally {
                fx.jdbc().execute("alter table documents enable trigger trg_search_documents_ins");
                fx.jdbc().execute("alter table documents enable trigger trg_search_documents_upd");
            }
            Path out = Path.of("target", "search-realpath-benchmark.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, report, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private void run(UUID seed, UUID versionRef) throws Exception {
        // ---- 1. load through PostgreSQL with the triggers ON ---------------------------------------------------------
        long t0 = System.nanoTime();
        fx.jdbc().update("""
                insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id,
                                       created_at, updated_at)
                select gen_random_uuid(), ?, ?, 'Benchmark ' || g || ' ' || (array['agreement','invoice','report','budget',
                       'contract','proposal','quarterly','annual','supplier','customer'])[1 + g % 10], ?, ?,
                       now() - (g % 1095) * interval '1 day', now() - (g % 365) * interval '1 day'
                  from generate_series(1, ?) g
                """, tenantId, reportType, versionRef, alice.getUserId(), N);
        double loadSeconds = (System.nanoTime() - t0) / 1e9;
        long queued = jobs.pendingCount(tenantId);
        say(String.format("load into PostgreSQL with triggers on: %,d documents in %.1f s (%,.0f docs/s); queue holds %,d jobs "
                + "(one per document: coalesced)", N, loadSeconds, N / loadSeconds, queued));
        assertThat(queued).as("one coalesced job per document").isEqualTo(N);

        // ---- 2. drain the queue through the real indexer ---------------------------------------------------------------
        AtomicBoolean draining = new AtomicBoolean(true);
        AtomicLong peakLag = new AtomicLong();
        Thread sampler = new Thread(() -> {
            while (draining.get()) {
                jobs.oldestDueAt(tenantId).ifPresent(t -> peakLag.accumulateAndGet(
                        Math.max(0, Duration.between(t, OffsetDateTime.now()).toSeconds()), Math::max));
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "lag-sampler");
        sampler.start();
        long d0 = System.nanoTime();
        long processed = 0;
        int n;
        while ((n = indexer.runOnce()) > 0 || jobs.pendingCount(tenantId) > 0) {
            processed += n;
            if (n == 0) {
                Thread.sleep(50);
            }
        }
        double drainSeconds = (System.nanoTime() - d0) / 1e9;
        draining.set(false);
        sampler.join();
        engine.refresh(indexAdmin.writeAlias());
        say(String.format("queue drain (one indexer loop, as one instance does): %,d jobs in %.1f s = %,.0f docs/s; "
                        + "peak lag %,d s (the whole load waiting at once); failed jobs %d",
                processed, drainSeconds, processed / drainSeconds, peakLag.get(), jobs.failedCount(tenantId)));
        assertThat(jobs.failedCount(tenantId)).isZero();
        assertThat(count()).as("every document indexed").isEqualTo(N);
        say("node: " + nodeMemory());

        // ---- 3. a tenant backfill (the generation sweep) ----------------------------------------------------------------
        long generation = tenantState.startRebuild(tenantId);
        long b0 = System.nanoTime();
        boolean swept = rebuild.finishTenantRebuild(tenantId, generation, Duration.ofHours(2));
        double backfillSeconds = (System.nanoTime() - b0) / 1e9;
        engine.refresh(indexAdmin.writeAlias());
        say(String.format("tenant backfill (generation sweep): %,d documents in %.1f s = %,.0f docs/s; swept=%s",
                N + 1L, backfillSeconds, (N + 1L) / backfillSeconds, swept));
        assertThat(swept).isTrue();
        assertThat(count()).isEqualTo(N + 1L);

        // ---- 4. a global rebuild with the alias swap while queries run ---------------------------------------------------
        AtomicBoolean querying = new AtomicBoolean(true);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong failures = new AtomicLong();
        AtomicLong queries = new AtomicLong();
        Thread queryThread = new Thread(() -> {
            while (querying.get()) {
                long q0 = System.nanoTime();
                try {
                    var result = mvc.perform(get("/api/v1/search").queryParam("q", "benchmark invoice").with(fx.jwtFor(alice))).andReturn();
                    queries.incrementAndGet();
                    if (result.getResponse().getStatus() != 200
                            || !JSON.readTree(result.getResponse().getContentAsString()).path("mode").asText().equals("search")) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
                latencies.add((System.nanoTime() - q0) / 1_000_000);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "rebuild-queries");
        queryThread.start();
        long g0 = System.nanoTime();
        SearchRebuildService.GlobalResult result = rebuild.rebuildAll(Duration.ofHours(2), true);
        double rebuildSeconds = (System.nanoTime() - g0) / 1e9;
        querying.set(false);
        queryThread.join();
        engine.refresh(indexAdmin.writeAlias());
        Collections.sort(latencies);
        long p95 = latencies.isEmpty() ? 0 : latencies.get((int) Math.min(latencies.size() - 1, Math.ceil(latencies.size() * 0.95) - 1));
        say(String.format("global rebuild with alias swap while queries run: %,d documents (every tenant in this database) in %.1f s; "
                        + "%s -> %s; %,d queries ran meanwhile, %d failed or fell back; p95 %d ms",
                result.documents(), rebuildSeconds, result.oldIndex(), result.newIndex(), queries.get(), failures.get(), p95));
        assertThat(failures.get()).as("no failed query during the rebuild and swap").isZero();
        assertThat(queries.get()).isPositive();
        assertThat(indexAdmin.currentIndex()).isEqualTo(result.newIndex());
        assertThat(count()).isEqualTo(N + 1L);

        // ---- 5. the reconciler's gap ------------------------------------------------------------------------------------
        long r0 = System.nanoTime();
        SearchReconciler.Gap gap = reconciler.reconcile(tenantId);
        say(String.format("reconciler at %,d documents: gap %d missing, %d orphans, %.1f s", N + 1L,
                gap.missing(), gap.orphans(), (System.nanoTime() - r0) / 1e9));
        assertThat(gap.missing()).isZero();
        assertThat(gap.orphans()).isZero();

        // ---- 6. memory -----------------------------------------------------------------------------------------------------
        say("node after everything: " + nodeMemory());
        Runtime rt = Runtime.getRuntime();
        say(String.format("this JVM (dms-core under test): heap used %d MB of max %d MB", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20));
        say("index size: " + indexSize());
    }

    private long count() throws Exception {
        return engine.count(indexAdmin.writeAlias(), tenantId.toString(),
                JSON.readTree("{\"term\":{\"tenant_id\":\"" + tenantId + "\"}}"));
    }

    private String nodeMemory() throws Exception {
        JsonNode stats = JSON.readTree(http("/_nodes/stats/jvm"));
        JsonNode node = stats.path("nodes").elements().next().path("jvm").path("mem");
        return String.format("OpenSearch heap %d MB used of %d MB (%d%%)", node.path("heap_used_in_bytes").asLong() >> 20,
                node.path("heap_max_in_bytes").asLong() >> 20, node.path("heap_used_percent").asInt());
    }

    private String indexSize() throws Exception {
        for (JsonNode row : JSON.readTree(http("/_cat/indices/" + indexAdmin.currentIndex() + "?format=json&bytes=mb"))) {
            return row.path("store.size").asText() + " MB, " + row.path("docs.count").asText()
                    + " index documents (nested metadata entries included)";
        }
        return "unknown";
    }

    private static String http(String path) throws Exception {
        String base = System.getenv().getOrDefault("OPENSEARCH_IT_URL", "http://localhost:9201");
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    /** 15 GB free on the Docker disk and 5 GB on /, or the benchmark refuses to start (and says why). */
    private void diskGuard() {
        File docker = new File(System.getenv().getOrDefault("SEARCH_BENCH_DOCKER_DISK", "/Volumes/ChamathSSD"));
        File root = new File("/");
        long dockerFree = (docker.exists() ? docker : root).getUsableSpace() >> 30;
        long rootFree = root.getUsableSpace() >> 30;
        say("disk free: Docker disk " + dockerFree + " GiB (" + (docker.exists() ? docker : root) + "), / " + rootFree + " GiB");
        assertThat(dockerFree).as("the Docker disk needs at least 15 GB free").isGreaterThanOrEqualTo(15);
        assertThat(rootFree).as("/ needs at least 5 GB free").isGreaterThanOrEqualTo(5);
    }
}
