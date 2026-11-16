package com.oak.dms.search.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.index.SearchSourceRepository;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The scale gate (DSR-NFR-001/002): search latency on a large repository, through the real API with both security layers.
 * Opt-in, never part of a normal run ({@code @Tag("perf")}, excluded by {@code it.excludedGroups}); run it with
 * {@code ./mvnw verify -Pperf -Dit.test=SearchScaleIT} (or {@code mvn test -Dtest=SearchScaleIT} for a smaller try). The size is
 * {@code -Dsearch.scale.documents=N} or {@code SEARCH_SCALE_DOCUMENTS=N}, default 500,000.
 *
 * <p>What it builds: N documents in PostgreSQL (generated in SQL, search triggers switched off for the load and the cleanup)
 * with realistic spread (types, departments, a folder tree, three years of dates, typed metadata) and, for the same
 * documents, index entries streamed straight into OpenSearch with a synthetic body (the real extraction path is covered
 * elsewhere; this measures querying). Five percent of documents are visible to the restricted-heavy user, so most
 * candidates are dropped by the PostgreSQL layer: the expensive case.
 *
 * <p>What it asserts (latency targets): p95 under 1 s for keyword+filter searches by someone who sees everything, p95 under
 * 2.5 s for the restricted-heavy user. What it reports (printed and written to {@code target/search-scale-gate.txt}): load
 * throughput, per-query-shape p50/p95/p99, concurrent throughput, index size, and JVM heap on the node.
 */
@Tag("perf")
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchScaleIT extends SearchApiSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int N = Integer.getInteger("search.scale.documents",
            System.getenv("SEARCH_SCALE_DOCUMENTS") == null ? 500_000 : Integer.parseInt(System.getenv("SEARCH_SCALE_DOCUMENTS")));
    private static final int PAGE = 5_000;
    private static final int RUNS = 30;

    @Autowired
    private SearchSourceRepository source;

    private final List<String> report = new ArrayList<>();

    private void say(String line) {
        report.add(line);
        System.out.println("[search-scale] " + line);
    }

    private record Shape(String name, long p50, long p95, long p99) {
    }

    @Test
    void searchStaysFastOnALargeRepository() throws Exception {
        say("documents: " + N + "  (index: 1 shard, 0 replicas, 512 MB heap, one node)");
        try {
            run();
        } finally {
            cleanup(); // while the search triggers are still off, or the delete would queue a job per document
            fx.jdbc().execute("alter table documents enable trigger trg_search_documents_ins");
            fx.jdbc().execute("alter table documents enable trigger trg_search_documents_upd");
            Path out = Path.of("target", "search-scale-gate.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, report, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private void run() throws Exception {
        UUID invoiceType = fx.documentType(tenantId, "Invoice");
        UUID memoType = fx.documentType(tenantId, "Memo");
        List<UUID> types = List.of(reportType, invoiceType, memoType);
        List<UUID> departments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            departments.add(fx.department(tenantId, "Dept " + i).getDepartmentId());
        }
        // a three-level folder tree: 5 top, 5 children each, 2 grandchildren each = 5 + 25 + 50 folders
        List<UUID> folders = new ArrayList<>();
        UUID restrictedFolderRoot = null;
        for (int a = 0; a < 5; a++) {
            UUID top = folder(null, "Top " + a);
            folders.add(top);
            if (a == 0) {
                restrictedFolderRoot = top;
            }
            for (int b = 0; b < 5; b++) {
                UUID mid = folder(top, "Mid " + a + "-" + b);
                folders.add(mid);
                for (int c = 0; c < 2; c++) {
                    folders.add(folder(mid, "Leaf " + a + "-" + b + "-" + c));
                }
            }
        }
        // carol: cannot see the first top folder or anything under it (about 1/5 of documents hidden by folder access)
        User carol2 = fx.user(tenantId, "Carol2");
        fx.role(tenantId, "Searcher2", new String[]{"dsr.search-documents", "docm.documents.view"}, carol2);
        fx.jdbc().update("update folders set access_mode = 'restricted' where folder_id = ?", restrictedFolderRoot);

        fx.jdbc().update("insert into metadata_fields (field_id, tenant_id, field_key, label, data_type, is_system_field, is_searchable, status)"
                + " values (?, ?, 'amount', 'Amount', 'number', false, true, 'active'), (?, ?, 'status', 'Status', 'dropdown', false, true, 'active')",
                AMOUNT, tenantId, STATUS, tenantId);
        UUID seed = fx.document(tenantId, reportType, alice, "Seed document");
        UUID versionRef = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, seed);

        // ---- 1. PostgreSQL population ------------------------------------------------------------------------------
        fx.jdbc().execute("alter table documents disable trigger trg_search_documents_ins");
        fx.jdbc().execute("alter table documents disable trigger trg_search_documents_upd");
        long t0 = System.nanoTime();
        String words = "array['agreement','invoice','report','budget','contract','proposal','quarterly','annual','supplier','customer',"
                + "'policy','audit','forecast','revenue','schedule','minutes','review','summary','statement','order',"
                + "'delivery','payment','tender','approval','compliance','strategy','project','payroll','insurance','warranty',"
                + "'license','lease','tax','expense','asset','risk','incident','training','handbook','roadmap']";
        String insert = """
                insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id,
                                       owner_department_id, folder_id, access_mode, created_at, updated_at)
                select gen_random_uuid(), ?, (?::uuid[])[1 + abs(hashtext('t' || g)) % 3],
                       initcap((@WORDS@)[1 + (g::bigint * 7919) % 40] || ' ' || (@WORDS@)[1 + (g::bigint * 104729) % 40]) || ' ' || g,
                       ?, ?, (?::uuid[])[1 + abs(hashtext('d' || g)) % 10], (?::uuid[])[1 + abs(hashtext('f' || g)) % 80],
                       case when abs(hashtext('v' || g)) % 20 = 0 then 'inherit' else 'restricted' end,
                       now() - ((g * 37) % 1095) * interval '1 day', now() - ((g * 17) % 365) * interval '1 day'
                  from generate_series(1, ?) g
                """.replace("@WORDS@", words);
        fx.jdbc().update(insert, tenantId, uuidArray(types), versionRef, alice.getUserId(), uuidArray(departments),
                uuidArray(folders), N);
        say(String.format("PostgreSQL population: %,d documents in %.1f s", N, (System.nanoTime() - t0) / 1e9));
        // Visibility, type, department and folder come from hashes of the row number, not from g % k: with plain modulos
        // they correlate with the title words (also functions of g), which makes the visible documents unrepresentative.
        // Restricted documents have no document_access rows, so only administrators see them.

        // ---- 2. stream the same documents into OpenSearch ------------------------------------------------------------
        indexAdmin.ensureIndex();
        indexAdmin.ensureTenantAlias(tenantId);
        String index = indexAdmin.currentIndex();
        engine.putSettings(index, JSON.readTree("{\"index\":{\"refresh_interval\":\"-1\"}}"));
        Map<UUID, List<UUID>> ancestors = new java.util.HashMap<>();
        for (UUID f : folders) {
            ancestors.put(f, source.folderAndAncestors(tenantId, f));
        }
        Map<UUID, String> typeNames = Map.of(reportType, "Report", invoiceType, "Invoice", memoType, "Memo");
        long loadStart = System.nanoTime();
        UUID after = null;
        long loaded = 0;
        String[] vocab = {"agreement", "invoice", "report", "budget", "contract", "proposal", "quarterly", "annual", "supplier",
                "customer", "policy", "audit", "forecast", "revenue", "schedule", "minutes", "review", "summary", "statement",
                "order", "delivery", "payment", "tender", "approval", "compliance", "strategy", "project", "payroll", "insurance",
                "warranty", "license", "lease", "tax", "expense", "asset", "risk", "incident", "training", "handbook", "roadmap"};
        while (true) {
            List<Map<String, Object>> rows = fx.jdbc().queryForList("""
                    select document_id, title, document_type_id, owner_department_id, folder_id, created_at, updated_at
                      from documents where tenant_id = ? and document_id <> ? and (?::uuid is null or document_id > ?::uuid)
                     order by document_id limit ?
                    """, tenantId, seed, after, after, PAGE);
            if (rows.isEmpty()) {
                break;
            }
            List<SearchEngine.BulkOp> ops = new ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                UUID id = (UUID) r.get("document_id");
                int h = Math.floorMod(id.hashCode(), 1 << 30); // non-negative and far from int overflow when the offsets below are added
                ObjectNode d = JSON.createObjectNode();
                d.put("tenant_id", tenantId.toString());
                d.put("document_id", id.toString());
                d.put("change_counter", 1);
                d.put("generation", 1);
                String title = (String) r.get("title");
                d.put("title", title);
                d.put("file_name", title.replace(' ', '_') + ".txt");
                UUID type = (UUID) r.get("document_type_id");
                d.put("document_type_id", type.toString());
                d.put("document_type_name", typeNames.get(type));
                d.put("owner_id", alice.getUserId().toString());
                d.put("owner_department_id", r.get("owner_department_id").toString());
                UUID folder = (UUID) r.get("folder_id");
                if (folder != null) {
                    d.put("folder_id", folder.toString());
                    var anc = d.putArray("ancestor_folder_ids");
                    ancestors.get(folder).forEach(a -> anc.add(a.toString()));
                }
                d.put("lifecycle_state", "active");
                d.put("legal_hold", false);
                d.put("is_deleted", false);
                d.put("purged", false);
                d.put("is_template", false);
                d.put("mime_type", "text/plain");
                d.put("body_status", "ready");
                d.put("created_at", ((java.sql.Timestamp) r.get("created_at")).toInstant().toString());
                d.put("updated_at", ((java.sql.Timestamp) r.get("updated_at")).toInstant().toString());
                d.put("indexed_at", Instant.now().toString());
                StringBuilder body = new StringBuilder();
                for (int w = 0; w < 40; w++) {
                    body.append(vocab[(h + w * 31 + w * w) % vocab.length]).append(' ');
                }
                if (h % 1000 == 0) {
                    body.append("rarecode").append(h % 7);
                }
                d.put("body", body.toString());
                var meta = d.putArray("metadata");
                meta.addObject().put("field_id", AMOUNT.toString()).put("key", "amount").put("v_num", (double) (h % 10_000));
                meta.addObject().put("field_id", STATUS.toString()).put("key", "status").put("v_kw", h % 3 == 0 ? "OPEN" : "CLOSED");
                d.put("metadata_text", h % 3 == 0 ? "OPEN" : "CLOSED");
                ops.add(SearchEngine.BulkOp.index(index, id.toString(), tenantId.toString(), 1, JSON.writeValueAsString(d)));
            }
            var result = engine.bulk(ops);
            assertThat(result.allOk()).as("bulk of %d", ops.size()).isTrue();
            loaded += rows.size();
            after = (UUID) rows.get(rows.size() - 1).get("document_id");
        }
        engine.putSettings(index, JSON.readTree("{\"index\":{\"refresh_interval\":\"1s\"}}"));
        engine.refresh(index);
        double loadSeconds = (System.nanoTime() - loadStart) / 1e9;
        say(String.format("indexing: %,d documents in %.1f s = %,.0f documents/s (bulk of %d)", loaded, loadSeconds, loaded / loadSeconds, PAGE));
        tenantState.ensure(tenantId);
        tenantState.setIndexReady(tenantId, true);
        assertThat(engine.count(indexAdmin.readAlias(tenantId), tenantId.toString(), JSON.readTree("{\"match_all\":{}}")))
                .as("every generated document except the seed was indexed").isEqualTo((long) N);

        // ---- 3. queries through the real API --------------------------------------------------------------------------
        for (int i = 0; i < 5; i++) {
            adminSearch("q", "agreement");
        }
        List<Shape> adminShapes = new ArrayList<>();
        adminShapes.add(measure("admin: common word", () -> adminSearch("q", pick(i0++, "agreement", "invoice", "report", "budget", "contract"))));
        adminShapes.add(measure("admin: rare word", () -> adminSearch("q", "rarecode" + (i0++ % 7))));
        adminShapes.add(measure("admin: two words", () -> adminSearch("q", pick(i0++, "annual budget", "supplier contract", "quarterly report", "payment order"))));
        adminShapes.add(measure("admin: quoted phrase", () -> adminSearch("q", pick(i0++, "\"annual budget\"", "\"supplier contract\"", "\"quarterly report\""))));
        adminShapes.add(measure("admin: prefix", () -> adminSearch("q", pick(i0++, "contr", "budg", "quart", "suppl"))));
        adminShapes.add(measure("admin: keyword + type + department + dates", () -> adminSearch(
                "q", pick(i0++, "report", "contract", "budget"), "type", invoiceType, "department", departments.get(i0 % 10),
                "createdFrom", "2025-01-01", "createdTo", "2026-12-31")));
        adminShapes.add(measure("admin: keyword + folder with subfolders", () -> adminSearch(
                "q", "agreement", "folderId", folders.get(0), "includeSubfolders", true)));
        adminShapes.add(measure("admin: keyword + metadata filters", () -> adminSearch(
                "q", pick(i0++, "report", "invoice"), "meta", AMOUNT + ":gte:5000", "meta", STATUS + ":eq:OPEN")));
        adminShapes.add(measure("admin: filters only, sorted by title", () -> adminSearch(
                "type", memoType, "sort", "title", "dir", "asc")));
        adminShapes.add(measure("admin: no keywords, newest first", () -> adminSearch("size", 20)));

        // deep paging: ten pages by cursor
        List<Long> pageTimes = new ArrayList<>();
        String cursor = null;
        for (int p = 0; p < 10; p++) {
            long s = System.nanoTime();
            JsonNode r = cursor == null ? adminSearch("q", "agreement") : adminSearch("q", "agreement", "cursor", cursor);
            pageTimes.add((System.nanoTime() - s) / 1_000_000);
            cursor = r.get("nextCursor").isNull() ? null : r.get("nextCursor").asText();
            if (cursor == null) {
                break;
            }
        }
        say("admin: ten pages by cursor, ms per page " + pageTimes);

        List<Shape> restrictedShapes = new ArrayList<>();
        restrictedShapes.add(measure("restricted-heavy (5% visible): common word", () -> asBob("q", pick(i0++, "agreement", "invoice", "report", "budget"))));
        restrictedShapes.add(measure("restricted-heavy (5% visible): two words", () -> asBob("q", pick(i0++, "annual budget", "supplier contract", "quarterly report"))));
        restrictedShapes.add(measure("restricted-heavy (5% visible): filters only", () -> asBob("type", reportType)));
        int refine = 0;
        for (int i = 0; i < 20; i++) {
            if (noticeCodes(asBob("q", pick(i, "agreement", "invoice", "report"))).contains("REFINE")) {
                refine++;
            }
        }
        say("restricted-heavy: " + refine + " of 20 first pages hit a cap and asked the user to refine");

        // concurrency: 8 users at once
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Long>> futures = new ArrayList<>();
        long cStart = System.nanoTime();
        for (int t = 0; t < 8; t++) {
            final int seed2 = t;
            futures.add(pool.submit(() -> {
                long worst = 0;
                for (int i = 0; i < 25; i++) {
                    long s = System.nanoTime();
                    adminSearch("q", pick(seed2 + i, "agreement", "invoice", "report", "budget", "contract"));
                    worst = Math.max(worst, (System.nanoTime() - s) / 1_000_000);
                }
                return worst;
            }));
        }
        long worstConcurrent = 0;
        for (Future<Long> f : futures) {
            worstConcurrent = Math.max(worstConcurrent, f.get());
        }
        pool.shutdown();
        double cSeconds = (System.nanoTime() - cStart) / 1e9;
        say(String.format("concurrency: 8 users x 25 searches in %.1f s = %.0f searches/s, slowest %d ms", cSeconds, 200 / cSeconds, worstConcurrent));

        // ---- 4. the node ----------------------------------------------------------------------------------------------
        JsonNode stats = JSON.readTree(new java.net.URL(com.oak.dms.search.SearchIntegrationTestSupport.url() + "/_nodes/stats/jvm").openStream());
        JsonNode jvm = stats.get("nodes").elements().next().get("jvm").get("mem");
        say(String.format("OpenSearch JVM heap: %d%% used of %d MB", jvm.get("heap_used_percent").asInt(), jvm.get("heap_max_in_bytes").asLong() / (1024 * 1024)));
        JsonNode cat = JSON.readTree(new java.net.URL(com.oak.dms.search.SearchIntegrationTestSupport.url()
                + "/_cat/indices/" + indexAdmin.currentIndex() + "?format=json&bytes=mb").openStream());
        say("index size: " + cat.get(0).get("store.size").asText() + " MB for " + cat.get(0).get("docs.count").asText() + " documents");

        // ---- 5. report and gate -----------------------------------------------------------------------------------------
        say(String.format("%-62s %7s %7s %7s", "query shape (ms)", "p50", "p95", "p99"));
        for (Shape s : concat(adminShapes, restrictedShapes)) {
            say(String.format("%-62s %7d %7d %7d", s.name(), s.p50(), s.p95(), s.p99()));
        }
        for (Shape s : adminShapes) {
            assertThat(s.p95()).as("p95 of '%s' (ms)", s.name()).isLessThan(1000);
        }
        for (Shape s : restrictedShapes) {
            assertThat(s.p95()).as("p95 of '%s' (ms)", s.name()).isLessThan(2500);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static final UUID AMOUNT = UUID.randomUUID();
    private static final UUID STATUS = UUID.randomUUID();
    private int i0 = 0;

    private JsonNode adminSearch(Object... pairs) {
        return asUser(true, tenantAdmin, pairs);
    }

    private JsonNode asBob(Object... pairs) {
        return asUser(false, bob, pairs);
    }

    private JsonNode asUser(boolean admin, User user, Object... pairs) {
        try {
            var jwt = admin ? fx.tenantAdminJwtFor(user) : fx.jwtFor(user);
            String body = mvc.perform(request("/api/v1/search", pairs).with(jwt)).andReturn().getResponse().getContentAsString();
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> noticeCodes(JsonNode r) {
        List<String> out = new ArrayList<>();
        r.get("notices").forEach(n -> out.add(n.get("code").asText()));
        return out;
    }

    private Shape measure(String name, Supplier<JsonNode> query) {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long s = System.nanoTime();
            JsonNode r = query.get();
            times.add((System.nanoTime() - s) / 1_000_000);
            assertThat(r.get("mode").asText()).as(name).isEqualTo("search");
        }
        Collections.sort(times);
        return new Shape(name, times.get(RUNS / 2), times.get((int) Math.ceil(RUNS * 0.95) - 1), times.get(RUNS - 1));
    }

    private static <T> T pick(int i, T... options) {
        return options[Math.abs(i) % options.length];
    }

    private static List<Shape> concat(List<Shape> a, List<Shape> b) {
        List<Shape> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String uuidArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    /** Removes everything this run created from PostgreSQL (triggers are still off) and from the engine. */
    private void cleanup() {
        // the generated documents have no versions of their own; the one seed document (with its version) stays
        fx.jdbc().update("delete from documents d where d.tenant_id = ? and not exists "
                + "(select 1 from document_versions v where v.document_id = d.document_id)", tenantId);
        fx.jdbc().update("delete from search_index_jobs where tenant_id = ?", tenantId);
    }
}
