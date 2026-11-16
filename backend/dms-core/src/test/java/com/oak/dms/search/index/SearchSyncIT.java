package com.oak.dms.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.OcrArtifactWriter;
import com.oak.dms.search.SearchIntegrationTestSupport;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.engine.SearchEngineException;
import com.oak.dms.search.extract.TextArtifactStore;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * The index stays in step with the repository: every event that changes what is searchable is picked up by the
 * triggers and reaches the index through the real indexer (D8, D10), and the queue behaves under retries, crashes,
 * out-of-order jobs and concurrent workers.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchSyncIT {

    private static final String PREFIX = "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @DynamicPropertySource
    static void search(DynamicPropertyRegistry registry) {
        registry.add("dms.search.opensearch.url", SearchIntegrationTestSupport::url);
        registry.add("dms.search.index.prefix", () -> PREFIX);
    }

    @MockitoBean(name = "renditionGenerationListener")
    private Object backgroundRenditions;
    @MockitoSpyBean
    private SearchEngine engine;

    @Autowired
    private SearchIndexer indexer;
    @Autowired
    private IndexAdmin admin;
    @Autowired
    private SearchJobRepository jobs;
    @Autowired
    private SearchTenantStateRepository tenantState;
    @Autowired
    private SearchProperties props;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WorkflowFixtures fx;
    @Autowired
    private DocumentVersionRepository versions;
    @Autowired
    private OcrArtifactWriter ocrArtifacts;
    @Autowired
    private StorageLocationResolver resolver;
    @Autowired
    private ObjectStorageServiceFactory storage;

    private SearchSyncSupport s;
    private UUID tenant;
    private User owner;
    private UUID type;

    @BeforeEach
    void setUp() {
        SearchIntegrationTestSupport.assumeAvailable();
        Mockito.reset(engine);
        s = new SearchSyncSupport(engine, admin, indexer, jdbc, fx);
        tenant = fx.tenant();
        owner = fx.user(tenant, "Indexer");
        type = fx.documentType(tenant, "Contract");
        // other tests' leftovers must not be claimed by this test's indexer runs
        jdbc.update("delete from search_index_jobs");
        admin.forgetTenantAliases();
    }

    @AfterAll
    static void cleanUp() {
        SearchProperties p = new SearchProperties();
        p.getIndex().setPrefix(PREFIX);
        SearchIntegrationTestSupport.cleanup(p);
    }

    // ------------------------------------------------------------------ what gets indexed

    @Test
    void aNewDocumentIsIndexedWithItsFieldsFolderAncestorsAndBody() {
        UUID parent = s.folder(tenant, null, "Legal");
        UUID child = s.folder(tenant, parent, "Contracts");
        UUID doc = s.textDocument(tenant, type, owner, "Supply agreement", "supply_agreement.txt", "The supplier shall deliver widgets.");
        jdbc.update("update documents set folder_id = ? where document_id = ?", child, doc);

        assertThat(s.drain()).isGreaterThan(0);

        JsonNode d = s.source(tenant, doc);
        assertThat(d).isNotNull();
        assertThat(d.path("title").asText()).isEqualTo("Supply agreement");
        assertThat(d.path("file_name").asText()).isEqualTo("supply_agreement.txt");
        assertThat(d.path("tenant_id").asText()).isEqualTo(tenant.toString());
        assertThat(d.path("owner_id").asText()).isEqualTo(owner.getUserId().toString());
        assertThat(d.path("document_type_name").asText()).isEqualTo("Contract");
        assertThat(d.path("lifecycle_state").asText()).isEqualTo("active");
        assertThat(d.path("body_status").asText()).isEqualTo("ready");
        assertThat(d.has("body")).as("body text is never returned in _source").isFalse();
        assertThat(texts(d.path("ancestor_folder_ids"))).containsExactlyInAnyOrder(child.toString(), parent.toString());
        // the body is searchable although it is not in _source
        assertThat(s.ids(tenant, SearchSyncSupport.match("body", "widgets"))).containsExactly(doc.toString());
        assertThat(s.jobs(tenant)).as("queue drained").isZero();
    }

    @Test
    void renameMovesAndFolderMovesAreReflected() {
        UUID a = s.folder(tenant, null, "A");
        UUID b = s.folder(tenant, null, "B");
        UUID sub = s.folder(tenant, a, "Sub");
        UUID doc = s.textDocument(tenant, type, owner, "Original title", "f.txt", "text");
        jdbc.update("update documents set folder_id = ? where document_id = ?", sub, doc);
        s.drain();
        assertThat(texts(s.source(tenant, doc).path("ancestor_folder_ids"))).containsExactlyInAnyOrder(sub.toString(), a.toString());

        jdbc.update("update documents set title = 'Renamed title' where document_id = ?", doc);
        s.drain();
        assertThat(s.source(tenant, doc).path("title").asText()).isEqualTo("Renamed title");
        assertThat(s.ids(tenant, SearchSyncSupport.match("title", "renamed"))).containsExactly(doc.toString());
        assertThat(s.ids(tenant, SearchSyncSupport.match("title", "original"))).isEmpty();

        // moving the FOLDER changes the ancestors of every document under it (one subtree job, expanded by the indexer)
        jdbc.update("update folders set parent_folder_id = ? where folder_id = ?", b, a);
        s.drain();
        assertThat(texts(s.source(tenant, doc).path("ancestor_folder_ids"))).containsExactlyInAnyOrder(sub.toString(), a.toString(), b.toString());

        // moving the document itself
        jdbc.update("update documents set folder_id = null where document_id = ?", doc);
        s.drain();
        assertThat(s.source(tenant, doc).path("ancestor_folder_ids")).isEmpty();
    }

    @Test
    void metadataIsIndexedAsRankingTextAndTypedFilterSlots_andOnlyForSearchableFields() {
        UUID amount = field("amount", "number", true);
        UUID status = field("status", "dropdown", true);
        UUID due = field("due", "date", true);
        UUID paid = field("paid", "boolean", true);
        UUID secret = field("secret_note", "text", false);
        jdbc.update("insert into metadata_field_options (tenant_id, field_id, option_value, option_label) values (?, ?, 'APPR', 'Approved by legal')",
                tenant, status);
        UUID doc = s.textDocument(tenant, type, owner, "With metadata", "m.txt", "x");
        jdbc.update("insert into document_type_schemas (tenant_id, document_type_id, schema_version) values (?, ?, 1)", tenant, type);
        jdbc.update("insert into document_metadata (tenant_id, document_id, document_type_id, schema_version, field_values) values (?, ?, ?, 1, ?::jsonb)",
                tenant, doc, type, "{\"" + amount + "\":{\"v\":1500.5,\"src\":\"manual\"},\"" + status + "\":{\"v\":\"APPR\",\"src\":\"manual\"},"
                        + "\"" + due + "\":{\"v\":\"2026-03-01\",\"src\":\"manual\"},\"" + paid + "\":{\"v\":true,\"src\":\"manual\"},"
                        + "\"" + secret + "\":{\"v\":\"classified remark\",\"src\":\"manual\"}}");
        s.drain();

        JsonNode d = s.source(tenant, doc);
        assertThat(d.path("metadata_text").asText()).contains("Approved by legal").contains("1500.5").doesNotContain("classified");
        assertThat(d.path("metadata")).hasSize(4);
        assertThat(s.ids(tenant, "{\"nested\":{\"path\":\"metadata\",\"query\":{\"bool\":{\"filter\":[{\"term\":{\"metadata.field_id\":\"" + amount
                + "\"}},{\"range\":{\"metadata.v_num\":{\"gte\":1000}}}]}}}}")).containsExactly(doc.toString());
        assertThat(s.ids(tenant, SearchSyncSupport.match("metadata_text", "approved"))).containsExactly(doc.toString());
        assertThat(s.ids(tenant, SearchSyncSupport.match("metadata_text", "classified"))).isEmpty();

        // an edit is picked up
        jdbc.update("update document_metadata set field_values = ?::jsonb where document_id = ?",
                "{\"" + status + "\":{\"v\":\"APPR\",\"src\":\"manual\"}}", doc);
        s.drain();
        assertThat(s.source(tenant, doc).path("metadata")).hasSize(1);
    }

    @Test
    void ownerDepartmentLifecycleAndLegalHoldChangesAreReflected() {
        User other = fx.user(tenant, "Other");
        var dept = fx.department(tenant, "Finance", other);
        UUID doc = s.textDocument(tenant, type, owner, "Governed", "g.txt", "x");
        s.drain();

        jdbc.update("update documents set owner_user_id = ?, owner_department_id = ? where document_id = ?",
                other.getUserId(), dept.getDepartmentId(), doc);
        s.drain();
        JsonNode d = s.source(tenant, doc);
        assertThat(d.path("owner_id").asText()).isEqualTo(other.getUserId().toString());
        assertThat(d.path("owner_department_id").asText()).isEqualTo(dept.getDepartmentId().toString());

        jdbc.update("update documents set lifecycle_state = 'archived' where document_id = ?", doc);
        s.drain();
        assertThat(s.source(tenant, doc).path("lifecycle_state").asText()).isEqualTo("archived");

        jdbc.update("update documents set legal_hold = true, legal_hold_at = now(), legal_hold_by = ?, legal_hold_reason = 'case' where document_id = ?",
                owner.getUserId(), doc);
        s.drain();
        assertThat(s.source(tenant, doc).path("legal_hold").asBoolean()).isTrue();
    }

    @Test
    void workflowStatusChangesAreNotIndexedAndEnqueueNothing() {
        UUID doc = s.textDocument(tenant, type, owner, "In review", "r.txt", "x");
        s.drain();
        jdbc.update("update documents set status = 'published' where document_id = ?", doc);
        assertThat(s.jobsFor(doc)).as("status is not indexed (workflow-readable access is checked in PostgreSQL per query)").isZero();
    }

    @Test
    void recycleBinDeleteRestoreAndPurgeAreReflected_andPurgeRemovesTheCachedText() {
        UUID doc = s.textDocument(tenant, type, owner, "Short lived", "s.txt", "ephemeral words");
        s.drain();
        assertThat(s.indexed(tenant, doc)).isTrue();
        UUID version = s.versionOf(doc);
        StorageLocation loc = resolver.resolveForTenant(tenant, StoragePurpose.document);
        String cacheKey = TextArtifactStore.keyFor(tenant, doc, version);
        assertThat(storage.resolve(loc).exists(loc, cacheKey)).as("text cached by the first extraction").isTrue();

        jdbc.update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", doc);
        s.drain();
        assertThat(s.indexed(tenant, doc)).as("a Recycle Bin document is not searchable").isFalse();

        jdbc.update("update documents set is_deleted = false, deleted_at = null where document_id = ?", doc);
        s.drain();
        assertThat(s.indexed(tenant, doc)).as("restored").isTrue();

        jdbc.update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", doc);
        DocumentVersion v = versions.findById(version).orElseThrow();
        ocrArtifacts.deleteFor(v); // the purge path
        jdbc.update("update documents set purged_at = now() where document_id = ?", doc);
        s.drain();
        assertThat(s.indexed(tenant, doc)).as("purged").isFalse();
        assertThat(storage.resolve(loc).exists(loc, cacheKey)).as("the cached text goes with the purge").isFalse();
    }

    @Test
    void aNewVersionReplacesTheIndexedFileNameAndBody() {
        UUID doc = s.textDocument(tenant, type, owner, "Versioned", "v1.txt", "first edition wording");
        s.drain();
        assertThat(s.ids(tenant, SearchSyncSupport.match("body", "edition"))).containsExactly(doc.toString());

        UUID v2 = fx.newVersion(tenant, doc, owner);
        jdbc.update("update document_versions set original_filename = 'v2.txt', mime_type = 'text/plain', size_bytes = 20 where version_id = ?", v2);
        fx.putVersionContent(v2, "second revision text".getBytes());
        s.drain();
        JsonNode d = s.source(tenant, doc);
        assertThat(d.path("file_name").asText()).isEqualTo("v2.txt");
        assertThat(d.path("version_number").asInt()).isEqualTo(2);
        assertThat(s.ids(tenant, SearchSyncSupport.match("body", "revision"))).containsExactly(doc.toString());
        assertThat(s.ids(tenant, SearchSyncSupport.match("body", "edition"))).as("only the latest version's text is indexed").isEmpty();
    }

    @Test
    void bodyGoesFromPendingToReadyWhenThePreviewOrOcrFinishes() throws Exception {
        UUID doc = fx.document(tenant, type, owner, "Slides");
        UUID version = s.versionOf(doc);
        jdbc.update("update document_versions set original_filename = 'deck.pptx', mime_type = 'application/vnd.ms-powerpoint', rendition_status = 'processing' where version_id = ?", version);
        s.drain();
        assertThat(s.source(tenant, doc).path("body_status").asText()).isEqualTo("pending");

        // the preview PDF arrives: the rendition trigger re-queues and the body becomes searchable
        DocumentVersion v = versions.findById(version).orElseThrow();
        StorageLocation loc = resolver.resolveForTenant(tenant, StoragePurpose.document);
        byte[] pdf = pdf("quarterly projections slide");
        String key = "rendition/" + version + "/preview.pdf";
        storage.resolve(loc).upload(loc, key, new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
        jdbc.update("update document_versions set rendition_status = 'completed', rendition_object_key = ? where version_id = ?", key, version);
        s.drain();
        assertThat(s.source(tenant, doc).path("body_status").asText()).isEqualTo("ready");
        assertThat(s.ids(tenant, SearchSyncSupport.match("body", "projections"))).containsExactly(doc.toString());
    }

    @Test
    void anUnreadableFileIsStillSearchableByTitleAndFileName() {
        UUID doc = fx.document(tenant, type, owner, "Locked drawing");
        UUID version = s.versionOf(doc);
        jdbc.update("update document_versions set original_filename = 'plan.dwg', mime_type = 'application/acad', rendition_status = 'unsupported', ocr_status = 'not_required' where version_id = ?", version);
        s.drain();
        JsonNode d = s.source(tenant, doc);
        assertThat(d.path("body_status").asText()).isEqualTo("unavailable");
        assertThat(d.path("body_unavailable_reason").asText()).isEqualTo("unsupported");
        assertThat(s.ids(tenant, SearchSyncSupport.match("title", "drawing"))).containsExactly(doc.toString());
        assertThat(s.ids(tenant, SearchSyncSupport.match("file_name", "plan"))).containsExactly(doc.toString());
    }

    @Test
    void removingATenantRemovesItsDocumentsAndAlias_andDisablingSearchRemovesThemToo() {
        UUID doc = s.textDocument(tenant, type, owner, "Tenant doc", "t.txt", "x");
        s.drain();
        assertThat(s.countInIndex(tenant)).isEqualTo(1);

        // the per-tenant switch off: the next change removes the document from the index
        tenantState.setEnabled(tenant, false);
        jdbc.update("update documents set title = 'Tenant doc 2' where document_id = ?", doc);
        s.drain();
        assertThat(s.indexed(tenant, doc)).isFalse();
        tenantState.setEnabled(tenant, true);
        jdbc.update("update documents set title = 'Tenant doc 3' where document_id = ?", doc);
        s.drain();
        assertThat(s.indexed(tenant, doc)).isTrue();

        jdbc.update("update tenants set is_deleted = true, deleted_at = now() where tenant_id = ?", tenant);
        s.drain();
        assertThat(admin.tenantAliasExists(tenant)).as("the alias is dropped with the tenant").isFalse();
        jdbc.update("update tenants set is_deleted = false, deleted_at = null where tenant_id = ?", tenant);
    }

    // ------------------------------------------------------------------ the queue

    @Test
    void anOlderJobNeverOverwritesNewerIndexedData() throws Exception {
        UUID doc = s.textDocument(tenant, type, owner, "Real title", "o.txt", "x");
        admin.ensureIndex();
        admin.ensureTenantAlias(tenant);
        // a "newer" write already reached the index (external version far above any job's counter)
        engine.bulk(List.of(SearchEngine.BulkOp.index(admin.currentIndex(), doc.toString(), tenant.toString(), 9_000_000_000L,
                "{\"tenant_id\":\"" + tenant + "\",\"document_id\":\"" + doc + "\",\"title\":\"NEWER WINS\",\"change_counter\":9000000000}")));
        s.drain();
        assertThat(s.jobs(tenant)).as("the late job still completes").isZero();
        assertThat(s.source(tenant, doc).path("title").asText()).isEqualTo("NEWER WINS");
    }

    @Test
    void failuresRetryWithBackoff_thenParkAsFailed_andANewChangeRevivesThem() {
        UUID doc = s.textDocument(tenant, type, owner, "Flaky", "f.txt", "x");
        Mockito.doThrow(new SearchEngineException("boom")).when(engine).bulk(anyList());

        indexer.runOnce();
        var row = jdbc.queryForMap("select status, attempts, last_error, next_attempt_at > now() as later from search_index_jobs where document_id = ?", doc);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("bulk failed");
        assertThat(row.get("later")).as("backed off").isEqualTo(true);
        assertThat(indexer.runOnce()).as("not due yet").isZero();

        // the engine recovers: when due again the job succeeds
        Mockito.reset(engine);
        jdbc.update("update search_index_jobs set next_attempt_at = now() where document_id = ?", doc);
        s.drain();
        assertThat(s.indexed(tenant, doc)).isTrue();

        // exhausting attempts parks the job
        UUID doc2 = s.textDocument(tenant, type, owner, "Hopeless", "h.txt", "x");
        jdbc.update("update search_index_jobs set max_attempts = 2 where document_id = ?", doc2);
        Mockito.doThrow(new SearchEngineException("boom")).when(engine).bulk(anyList());
        indexer.runOnce();
        jdbc.update("update search_index_jobs set next_attempt_at = now() where document_id = ?", doc2);
        indexer.runOnce();
        assertThat(jdbc.queryForObject("select status from search_index_jobs where document_id = ?", String.class, doc2)).isEqualTo("failed");
        assertThat(indexer.runOnce()).as("parked jobs are not retried").isZero();

        // a new real change gives the document a fresh start
        Mockito.reset(engine);
        jdbc.update("update documents set title = 'Revived' where document_id = ?", doc2);
        assertThat(jdbc.queryForObject("select status from search_index_jobs where document_id = ?", String.class, doc2)).isEqualTo("pending");
        s.drain();
        assertThat(s.source(tenant, doc2).path("title").asText()).isEqualTo("Revived");
    }

    @Test
    void aCrashedWorkersJobsAreRecoveredAfterTheirLeaseExpires() {
        UUID doc = s.textDocument(tenant, type, owner, "Orphaned lease", "c.txt", "x");
        admin.ensureIndex();
        admin.ensureTenantAlias(tenant);
        // a worker claims the job and dies without finishing it
        var claimed = jobs.claim(props.getIndexer().getLease(), 10);
        assertThat(claimed).hasSize(1);
        assertThat(indexer.runOnce()).as("the lease is still held").isZero();
        assertThat(s.indexed(tenant, doc)).isFalse();

        jdbc.update("update search_index_jobs set locked_until = now() - interval '1 second' where document_id = ?", doc);
        s.drain();
        assertThat(s.indexed(tenant, doc)).as("another worker picked it up").isTrue();
    }

    @Test
    void aChangeThatArrivesWhileAJobRunsIsNeverLost() {
        UUID doc = s.textDocument(tenant, type, owner, "Before", "p.txt", "x");
        AtomicBoolean injected = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            if (injected.compareAndSet(false, true)) {
                // another transaction commits a rename between the job's read and its completion
                jdbc.update("update documents set title = 'After' where document_id = ?", doc);
            }
            return invocation.callRealMethod();
        }).when(engine).bulk(anyList());

        indexer.runOnce();
        assertThat(s.jobsFor(doc)).as("the job survives because its counter moved on").isEqualTo(1);
        s.drain();
        assertThat(s.source(tenant, doc).path("title").asText()).isEqualTo("After");
        assertThat(s.jobsFor(doc)).isZero();
    }

    @Test
    void whileTheEngineIsDownTheQueueIsLeftUntouched() {
        UUID doc = s.textDocument(tenant, type, owner, "Waiting", "w.txt", "x");
        Mockito.doReturn(SearchEngine.Health.DOWN).when(engine).health();
        assertThat(indexer.runOnce()).isZero();
        assertThat(jdbc.queryForObject("select attempts from search_index_jobs where document_id = ?", Integer.class, doc))
                .as("no attempt is burned while the engine is down").isZero();
        Mockito.reset(engine);
        s.drain();
        assertThat(s.indexed(tenant, doc)).isTrue();
    }

    @Test
    void manyWorkersNeverProcessTheSameJobTwice() throws Exception {
        int n = 40;
        List<UUID> docs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            docs.add(s.textDocument(tenant, type, owner, "Concurrent " + i, "c" + i + ".txt", "word" + i));
        }
        List<String> written = Collections.synchronizedList(new ArrayList<>());
        Mockito.doAnswer(invocation -> {
            List<SearchEngine.BulkOp> ops = invocation.getArgument(0);
            ops.forEach(op -> written.add(op.id()));
            return invocation.callRealMethod();
        }).when(engine).bulk(anyList());
        props.getIndexer().setBatchSize(5);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            futures.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 30; i++) {
                    indexer.runOnce();
                }
                return null;
            }));
        }
        go.countDown();
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();
        props.getIndexer().setBatchSize(50);
        s.drain();

        assertThat(s.countInIndex(tenant)).isEqualTo(n);
        assertThat(s.jobs(tenant)).isZero();
        assertThat(written).as("each document was written exactly once").hasSize(n).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ helpers

    private UUID field(String key, String dataType, boolean searchable) {
        return jdbc.queryForObject("insert into metadata_fields (tenant_id, field_key, label, data_type, is_system_field, is_searchable, status)"
                + " values (?, ?, ?, ?, false, ?, 'active') returning field_id", UUID.class, tenant, key, key, dataType, searchable);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static byte[] pdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
