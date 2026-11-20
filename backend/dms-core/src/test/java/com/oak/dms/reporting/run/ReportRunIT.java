package com.oak.dms.reporting.run;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Report runs through the real application: the asynchronous worker, real files in storage, the audit trail, and every refusal.
 * (brief section 7: access, cross-user, settings effects, lifecycle, audit, idempotency of a claim.)
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class ReportRunIT extends WorkflowApiSupport {

    static final String[] BUILDER = {"docm.documents.view", "crg.generate-custom-reports.build", "crg.generate-custom-reports.run"};
    static final String[] RUN_ONLY = {"docm.documents.view", "crg.generate-custom-reports.run"};
    static final String[] NO_RUN = {"docm.documents.view", "crg.generate-custom-reports.build"};

    /**
     * A background job asks Keycloak, live, which realm roles its user holds (and fails if it cannot - by design). The fixture users do
     * not exist in Keycloak, so it is stubbed here: it answers "no elevated role", which every user in this IT is.
     */
    @MockitoBean
    KeycloakUserService keycloak;
    @Autowired
    ReportRunWorker worker;
    @Autowired
    ReportRunSweeper sweeper;
    @Autowired
    StorageLocationRepository locations;
    @Autowired
    ObjectStorageServiceFactory storage;

    private UUID tenantId;
    private UUID typeId;
    private User owner;
    private User builder;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        typeId = fx.documentType(tenantId, "Contract");
        owner = fx.user(tenantId, "Owner");
        builder = fx.user(tenantId, "Builder");
        fx.role(tenantId, "Builder " + UUID.randomUUID(), BUILDER, owner, builder);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> definition(List<String> columns, Object filters, String groupBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "documents");
        body.put("columns", columns);
        body.put("filters", filters == null ? List.of() : filters);
        body.put("sort", List.of(Map.of("field", "title", "direction", "asc")));
        if (groupBy != null) {
            body.put("groupBy", groupBy);
        }
        return body;
    }

    private static Map<String, Object> filter(String field, String operator, String... values) {
        return Map.of("field", field, "operator", operator, "values", List.of(values));
    }

    private static Map<String, Object> request(String format, Map<String, Object> definition) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definition", definition);
        body.put("format", format);
        return body;
    }

    private JsonNode create(User as, String format, Map<String, Object> definition, int expected) throws Exception {
        return postAs("/api/v1/reports/runs", as, request(format, definition), expected);
    }

    /** Polls until the run is no longer queued or running. */
    private JsonNode await(User as, String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000;
        while (true) {
            JsonNode run = get200("/api/v1/reports/runs/" + runId, as);
            String status = run.get("status").asString();
            if (!status.equals("queued") && !status.equals("running")) {
                return run;
            }
            assertThat(System.currentTimeMillis()).as("run " + runId + " still " + status).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private JsonNode runAndWait(User as, String format, Map<String, Object> definition) throws Exception {
        return await(as, create(as, format, definition, 202).get("runId").asString());
    }

    private MockHttpServletResponse download(User as, String runId) throws Exception {
        return getRaw("/api/v1/reports/runs/" + runId + "/download", fx.jwtFor(as));
    }

    private static List<CSVRecord> csv(MockHttpServletResponse response) throws Exception {
        String text = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(text))) {
            return parser.getRecords();
        }
    }

    private static List<String> firstColumn(List<CSVRecord> records) {
        return records.stream().map(r -> r.get(0)).toList();
    }

    private long audits(String runId, String action) {
        return fx.count("select count(*) from audit_events where object_id = ?::uuid and action = ?", runId, action);
    }

    private UUID insertRun(String status, String startedAt, User by, String scope, User forUser) {
        UUID id = UUID.randomUUID();
        fx.jdbc().update("insert into report_runs (run_id, tenant_id, requested_by, for_user_id, delivery_scope, trigger, format, "
                        + "status, definition_snapshot, started_at) values (?, ?, ?, ?, ?, 'manual', 'csv', ?, cast(? as jsonb), "
                        + (startedAt == null ? "null" : "now() - interval '" + startedAt + "'") + ")",
                id, tenantId, by.getUserId(), forUser == null ? null : forUser.getUserId(), scope, status,
                "{\"definition\":{\"source\":\"documents\",\"columns\":[\"title\"],\"filters\":[],\"sort\":[]},"
                        + "\"zone\":\"UTC\",\"title\":\"Custom report\"}");
        return id;
    }

    private String status(UUID runId) {
        return fx.jdbc().queryForObject("select status from report_runs where run_id = ?", String.class, runId);
    }

    private void restrictToOwner(UUID document) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", document);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) "
                + "values (?, ?, 'user', ?, 'manage')", tenantId, document, owner.getUserId());
    }

    private static final List<String> COLUMNS = List.of("title", "documentType", "status");

    // ------------------------------------------------------------------ the happy path

    @Test
    void aCsvRunCompletes_andTheFileCanBeDownloaded() throws Exception {
        fx.document(tenantId, typeId, owner, "Alpha contract");
        fx.document(tenantId, typeId, owner, "Beta contract");

        JsonNode run = runAndWait(builder, "csv", definition(COLUMNS, List.of(filter("title", "contains", "contract")), null));

        assertThat(run.get("status").asString()).isEqualTo("completed");
        assertThat(run.get("rowCount").asLong()).isEqualTo(2);
        assertThat(run.get("downloadable").asBoolean()).isTrue();
        assertThat(run.get("truncated").asBoolean()).isFalse();
        MockHttpServletResponse file = download(builder, run.get("runId").asString());
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentType()).startsWith("text/csv");
        assertThat(file.getHeader("Content-Disposition")).contains("attachment").contains(".csv").contains("Custom-report");
        List<CSVRecord> records = csv(file);
        List<String> first = firstColumn(records);
        int heading = first.indexOf("Title");
        assertThat(first.subList(heading + 1, first.size())).containsExactly("Alpha contract", "Beta contract");
        assertThat(first).contains("Generated for: Builder", "Rows: 2", "Filter: Title: contains \"contract\"");
    }

    @Test
    void excelAndPdfRunsProduceRealFiles() throws Exception {
        fx.document(tenantId, typeId, owner, "Alpha contract");

        JsonNode xlsx = runAndWait(builder, "xlsx", definition(COLUMNS, null, null));
        JsonNode pdf = runAndWait(builder, "pdf", definition(COLUMNS, null, null));

        MockHttpServletResponse excel = download(builder, xlsx.get("runId").asString());
        MockHttpServletResponse document = download(builder, pdf.get("runId").asString());
        assertThat(xlsx.get("status").asString()).isEqualTo("completed");
        assertThat(pdf.get("status").asString()).isEqualTo("completed");
        assertThat(excel.getContentType()).contains("spreadsheetml");
        assertThat(new String(excel.getContentAsByteArray(), 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        assertThat(document.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(document.getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    }

    @Test
    void aGroupedReportCarriesAHeaderPerGroupWithItsCount() throws Exception {
        fx.document(tenantId, typeId, owner, "A");
        fx.document(tenantId, typeId, owner, "B");
        UUID published = fx.document(tenantId, typeId, owner, "C");
        fx.jdbc().update("update documents set status = 'published' where document_id = ?", published);

        JsonNode run = runAndWait(builder, "csv", definition(List.of("title"), null, "status"));

        List<String> first = firstColumn(csv(download(builder, run.get("runId").asString())));
        assertThat(first).contains("Document status: draft (2 documents)", "Document status: published (1 document)", "Total: 3 documents");
    }

    @Test
    void theRunStoresExactlyWhatWasExecuted() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");

        JsonNode run = runAndWait(builder, "csv", definition(COLUMNS, List.of(filter("title", "contains", "doc")), null));

        String snapshot = fx.jdbc().queryForObject("select definition_snapshot::text from report_runs where run_id = ?::uuid",
                String.class, run.get("runId").asString());
        assertThat(snapshot).contains("\"zone\"").contains("\"columns\"").contains("\"contains\"").contains("doc");
    }

    // ------------------------------------------------------------------ access and ownership

    @Test
    void aRestrictedDocumentNeverReachesTheFile() throws Exception {
        fx.document(tenantId, typeId, owner, "Open document");
        UUID minutes = fx.document(tenantId, typeId, owner, "Board minutes");
        restrictToOwner(minutes);

        JsonNode asBuilder = runAndWait(builder, "csv", definition(List.of("title"), null, null));
        JsonNode asOwner = runAndWait(owner, "csv", definition(List.of("title"), null, null));

        String builderFile = new String(download(builder, asBuilder.get("runId").asString()).getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(asBuilder.get("rowCount").asLong()).isEqualTo(1);
        assertThat(builderFile).contains("Open document").doesNotContain("Board minutes").doesNotContainIgnoringCase("hidden");
        assertThat(asOwner.get("rowCount").asLong()).isEqualTo(2);
    }

    @Test
    void anotherUserCannotSeeDownloadCancelOrRetryARun_theAnswerIsAlwaysTheSame404() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");
        String id = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();
        User other = fx.user(tenantId, "Other");
        fx.role(tenantId, "Other " + UUID.randomUUID(), BUILDER, other);
        String missing = UUID.randomUUID().toString();

        for (String ghost : List.of(id, missing)) {
            assertThat(call(get("/api/v1/reports/runs/" + ghost), fx.jwtFor(other), null, 404).get("code").asString())
                    .isEqualTo("REPORT_RUN_NOT_FOUND");
            assertThat(download(other, ghost).getStatus()).isEqualTo(404);
            assertThat(call(post("/api/v1/reports/runs/" + ghost + "/cancel"), fx.jwtFor(other), null, 404).get("code").asString())
                    .isEqualTo("REPORT_RUN_NOT_FOUND");
            assertThat(call(post("/api/v1/reports/runs/" + ghost + "/retry"), fx.jwtFor(other), null, 404).get("code").asString())
                    .isEqualTo("REPORT_RUN_NOT_FOUND");
        }
        JsonNode list = get200("/api/v1/reports/runs", other);
        assertThat(list.toString()).doesNotContain(id);
    }

    @Test
    void aTenantNeverSeesAnotherTenantsRuns() throws Exception {
        String id = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();
        UUID otherTenant = fx.tenant();
        User stranger = fx.user(otherTenant, "Stranger");
        fx.role(otherTenant, "Builder " + UUID.randomUUID(), BUILDER, stranger);

        call(get("/api/v1/reports/runs/" + id), fx.jwtFor(stranger), null, 404);
        assertThat(get200("/api/v1/reports/runs", stranger).get("content")).isEmpty();
    }

    @Test
    void theListIsTheCallersOwn_newestFirst_andFiltersByStatus() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");
        String first = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();
        String second = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();
        runAndWait(owner, "csv", definition(COLUMNS, null, null));

        JsonNode mine = get200("/api/v1/reports/runs", builder).get("content");
        JsonNode completed = get200("/api/v1/reports/runs?status=completed", builder).get("content");
        JsonNode failed = get200("/api/v1/reports/runs?status=failed", builder).get("content");

        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).get("runId").asString()).isEqualTo(second);
        assertThat(mine.get(1).get("runId").asString()).isEqualTo(first);
        assertThat(completed).hasSize(2);
        assertThat(failed).isEmpty();
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void permissionsAreChecked_runToRunAndBuildToRunYourOwnDefinition() throws Exception {
        User runner = fx.user(tenantId, "Runner");
        fx.role(tenantId, "Runner " + UUID.randomUUID(), RUN_ONLY, runner);
        User noRun = fx.user(tenantId, "NoRun");
        fx.role(tenantId, "NoRun " + UUID.randomUUID(), NO_RUN, noRun);

        assertThat(create(runner, "csv", definition(COLUMNS, null, null), 403).get("code").asString()).isEqualTo("REPORT_BUILD_REQUIRED");
        create(noRun, "csv", definition(COLUMNS, null, null), 403);
    }

    @Test
    void theDownloadNeedsDocumentAccessAndTheRunPermissionEveryTime_notOnlyWhenTheRunWasMade() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");
        String id = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();
        assertThat(download(builder, id).getStatus()).isEqualTo(200);

        fx.jdbc().update("delete from role_permissions where role_id in (select role_id from user_roles where user_id = ?) "
                + "and permission_id = (select permission_id from permissions where permission_code = 'crg.generate-custom-reports.run' and tenant_id is null)",
                builder.getUserId());

        assertThat(download(builder, id).getStatus()).as("the run grant was taken away").isEqualTo(403);
    }

    // ------------------------------------------------------------------ settings take effect on the next request

    @Test
    void turningReportsOffRefusesNewRunsAndDownloadsAlike() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");
        String id = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();

        fx.jdbc().update("insert into tenant_report_settings (tenant_id, enabled) values (?, false)", tenantId);

        assertThat(create(builder, "csv", definition(COLUMNS, null, null), 403).get("code").asString()).isEqualTo("REPORTS_DISABLED");
        assertThat(download(builder, id).getStatus()).isEqualTo(403);
    }

    @Test
    void aFormatTheOrganisationHasNotEnabledIsRefused() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allowed_formats, default_format) values (?, ARRAY['csv'], 'csv')", tenantId);

        JsonNode refused = create(builder, "xlsx", definition(COLUMNS, null, null), 422);

        assertThat(refused.get("code").asString()).isEqualTo("REPORT_FORMAT_NOT_ALLOWED");
        create(builder, "csv", definition(COLUMNS, null, null), 202);
    }

    @Test
    void anInvalidDefinitionIsRefusedAtRequestTime_notQueuedAsAFailure() throws Exception {
        JsonNode refused = create(builder, "csv", definition(List.of("title", "password_hash"), null, null), 422);

        assertThat(refused.get("code").asString()).isEqualTo("REPORT_FIELD_UNAVAILABLE");
        assertThat(fx.count("select count(*) from report_runs where tenant_id = ?", tenantId)).isZero();
    }

    @Test
    void exactlyOneOfTemplateAndDefinition() throws Exception {
        Map<String, Object> both = request("csv", definition(COLUMNS, null, null));
        both.put("templateId", UUID.randomUUID());

        assertThat(postAs("/api/v1/reports/runs", builder, both, 422).get("code").asString()).isEqualTo("REPORT_RUN_INVALID");
        assertThat(postAs("/api/v1/reports/runs", builder, Map.of("format", "csv"), 422).get("code").asString()).isEqualTo("REPORT_RUN_INVALID");
    }

    // ------------------------------------------------------------------ results, limits

    @Test
    void aReportThatFindsNothingCompletesWithNoFile() throws Exception {
        JsonNode run = runAndWait(builder, "csv", definition(COLUMNS, List.of(filter("title", "equals", "nothing like this")), null));

        assertThat(run.get("status").asString()).isEqualTo("completed");
        assertThat(run.get("rowCount").asLong()).isZero();
        assertThat(run.get("downloadable").asBoolean()).isFalse();
        assertThat(call(get("/api/v1/reports/runs/" + run.get("runId").asString() + "/download"), fx.jwtFor(builder), null, 404)
                .get("code").asString()).isEqualTo("REPORT_NO_FILE");
    }

    @Test
    void overTheRowLimitTheRunFailsFast_andSaysHowManyMatched() throws Exception {
        for (int i = 0; i < 3; i++) {
            fx.document(tenantId, typeId, owner, "Doc " + i);
        }
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, max_rows) values (?, 2)", tenantId);

        JsonNode run = runAndWait(builder, "csv", definition(COLUMNS, null, null));

        assertThat(run.get("status").asString()).isEqualTo("failed");
        assertThat(run.get("errorCode").asString()).isEqualTo("REPORT_ROW_LIMIT_EXCEEDED");
        assertThat(run.get("errorMessage").asString()).contains("3 documents").contains("at most 2");
        assertThat(run.get("downloadable").asBoolean()).isFalse();
        assertThat(audits(run.get("runId").asString(), "REPORT_FAILED")).isEqualTo(1);
    }

    @Test
    void whenTheOrganisationChoseTruncate_theFileHoldsTheFirstRowsAndSaysSo() throws Exception {
        for (int i = 0; i < 3; i++) {
            fx.document(tenantId, typeId, owner, "Doc " + i);
        }
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, max_rows, over_limit_behaviour) values (?, 2, 'truncate_and_mark')", tenantId);

        JsonNode run = runAndWait(builder, "csv", definition(List.of("title"), null, null));

        assertThat(run.get("status").asString()).isEqualTo("completed");
        assertThat(run.get("truncated").asBoolean()).isTrue();
        assertThat(run.get("rowCount").asLong()).isEqualTo(2);
        List<String> first = firstColumn(csv(download(builder, run.get("runId").asString())));
        assertThat(first).contains("Truncated at 2 rows", "Doc 0", "Doc 1").doesNotContain("Doc 2");
    }

    @Test
    void aPdfIsNeverTruncated_itFailsInstead() throws Exception {
        for (int i = 0; i < 3; i++) {
            fx.document(tenantId, typeId, owner, "Doc " + i);
        }
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, max_pdf_rows, over_limit_behaviour) values (?, 2, 'truncate_and_mark')", tenantId);

        JsonNode run = runAndWait(builder, "pdf", definition(List.of("title"), null, null));

        assertThat(run.get("status").asString()).isEqualTo("failed");
        assertThat(run.get("errorCode").asString()).isEqualTo("REPORT_ROW_LIMIT_EXCEEDED");
    }

    // ------------------------------------------------------------------ audit

    @Test
    void everyActionIsAuditedOnce_andNeverWithFilterValuesRowsOrFileNames() throws Exception {
        fx.document(tenantId, typeId, owner, "Confidential merger plan");
        JsonNode run = runAndWait(builder, "csv", definition(COLUMNS, List.of(filter("title", "contains", "merger")), null));
        String id = run.get("runId").asString();
        download(builder, id);

        assertThat(audits(id, "REPORT_RUN_REQUESTED")).isEqualTo(1);
        assertThat(audits(id, "REPORT_GENERATED")).isEqualTo(1);
        assertThat(audits(id, "REPORT_DOWNLOADED")).isEqualTo(1);
        assertThat(audits(id, "REPORT_FAILED")).isZero();
        List<String> payloads = fx.jdbc().queryForList(
                "select coalesce(new_value::text, '') from audit_events where object_id = ?::uuid", String.class, id);
        assertThat(String.join(" ", payloads)).doesNotContain("merger").doesNotContain("Confidential").doesNotContain("@");
    }

    // ------------------------------------------------------------------ cancel, retry, rate limits

    @Test
    void aQueuedRunCanBeCancelled_andOnlyWhileItIsQueuedOrRunning() throws Exception {
        UUID queued = insertRun("queued", null, builder, "owner", null);

        JsonNode cancelled = call(post("/api/v1/reports/runs/" + queued + "/cancel"), fx.jwtFor(builder), null, 200);

        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(call(post("/api/v1/reports/runs/" + queued + "/cancel"), fx.jwtFor(builder), null, 409).get("code").asString())
                .isEqualTo("REPORT_RUN_STATE_INVALID");
        worker.run(queued);
        assertThat(status(queued)).as("a cancelled run is never started").isEqualTo("cancelled");
    }

    @Test
    void aFailedRunCanBeRetried_andNothingElseCan() throws Exception {
        for (int i = 0; i < 3; i++) {
            fx.document(tenantId, typeId, owner, "Doc " + i);
        }
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, max_rows) values (?, 2)", tenantId);
        String failed = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();

        JsonNode retry = call(post("/api/v1/reports/runs/" + failed + "/retry"), fx.jwtFor(builder), null, 202);

        assertThat(retry.get("runId").asString()).isNotEqualTo(failed);
        assertThat(retry.get("trigger").asString()).isEqualTo("retry");
        await(builder, retry.get("runId").asString());
        fx.jdbc().update("delete from tenant_report_settings where tenant_id = ?", tenantId);
        String ok = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();
        assertThat(call(post("/api/v1/reports/runs/" + ok + "/retry"), fx.jwtFor(builder), null, 409).get("code").asString())
                .isEqualTo("REPORT_RUN_NOT_RETRYABLE");
    }

    @Test
    void aUserWithTooManyRunsWaitingOrRunningIsToldToWait() throws Exception {
        for (int i = 0; i < 5; i++) {
            insertRun("queued", null, builder, "owner", null);
        }

        JsonNode refused = create(builder, "csv", definition(COLUMNS, null, null), 429);

        assertThat(refused.get("code").asString()).isEqualTo("REPORT_RATE_LIMITED");
        create(owner, "csv", definition(COLUMNS, null, null), 202); // someone else is not affected
    }

    @Test
    void anOrganisationAtItsConcurrencyLimitRefusesInteractiveRuns_andAQueuedRunWaitsUntilThereIsRoom() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, max_concurrent_runs) values (?, 1)", tenantId);
        UUID busy = insertRun("running", "1 minute", owner, "owner", null);
        UUID waiting = insertRun("queued", null, builder, "owner", null);

        assertThat(create(builder, "csv", definition(COLUMNS, null, null), 429).get("code").asString()).isEqualTo("REPORT_RATE_LIMITED");
        worker.run(waiting);
        assertThat(status(waiting)).as("no room: it waits, it is not failed").isEqualTo("queued");

        fx.jdbc().update("update report_runs set status = 'completed' where run_id = ?", busy);
        worker.run(waiting);
        assertThat(status(waiting)).isEqualTo("completed");
    }

    @Test
    void twoWorkersStartingTheSameRunProduceExactlyOneFile() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");
        UUID id = insertRun("queued", null, builder, "owner", null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> both = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> {
                    go.await();
                    worker.run(id);
                    return null;
                }));
            }
            go.countDown();
            for (var f : both) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(id)).isEqualTo("completed");
        assertThat(audits(id.toString(), "REPORT_GENERATED")).isEqualTo(1);
    }

    @Test
    void aRunForAPersonWhoIsNoLongerActiveFailsWithAPlainMessage() throws Exception {
        UUID id = insertRun("queued", null, builder, "owner", null);
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", builder.getUserId());

        worker.run(id);

        assertThat(status(id)).isEqualTo("failed");
        assertThat(fx.jdbc().queryForObject("select error_code from report_runs where run_id = ?", String.class, id))
                .isEqualTo("REPORT_REQUESTER_INACTIVE");
    }

    // ------------------------------------------------------------------ per-recipient generation

    @Test
    void aRunForARecipientIsGeneratedWithTheRecipientsAccess_andIsTheirsToDownload() throws Exception {
        fx.document(tenantId, typeId, owner, "Open document");
        UUID minutes = fx.document(tenantId, typeId, owner, "Board minutes");
        restrictToOwner(minutes); // only the owner is on its list; the builder is not

        UUID forOwner = insertRun("queued", null, builder, "recipient", owner);
        UUID forBuilder = insertRun("queued", null, builder, "recipient", builder);
        worker.run(forOwner);
        worker.run(forBuilder);

        assertThat(fx.jdbc().queryForObject("select row_count from report_runs where run_id = ?", Long.class, forOwner)).isEqualTo(2);
        assertThat(fx.jdbc().queryForObject("select row_count from report_runs where run_id = ?", Long.class, forBuilder)).isEqualTo(1);
        assertThat(download(owner, forOwner.toString()).getStatus()).as("the recipient can download theirs").isEqualTo(200);
        assertThat(download(owner, forBuilder.toString()).getStatus()).as("not someone else's recipient copy").isEqualTo(404);
        String ownersCopy = new String(download(owner, forOwner.toString()).getContentAsByteArray(), StandardCharsets.UTF_8);
        String buildersCopy = new String(download(builder, forBuilder.toString()).getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(ownersCopy).contains("Board minutes");
        assertThat(buildersCopy).doesNotContain("Board minutes");
    }

    // ------------------------------------------------------------------ lifecycle: expiry and stuck runs

    @Test
    void anExpiredFileIsGone_410_andTheSweepDeletesItAndMarksTheRunExpired() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");
        String id = runAndWait(builder, "csv", definition(COLUMNS, null, null)).get("runId").asString();
        Map<String, Object> row = fx.jdbc().queryForMap("select file_object_key, storage_location_id from report_runs where run_id = ?::uuid", id);
        StorageLocation location = locations.findByStorageLocationIdAndDeletedFalse((UUID) row.get("storage_location_id")).orElseThrow();
        String key = (String) row.get("file_object_key");
        assertThat(storage.resolve(location).exists(location, key)).isTrue();

        fx.jdbc().update("update report_runs set expires_at = now() - interval '1 hour' where run_id = ?::uuid", id);

        assertThat(download(builder, id).getStatus()).isEqualTo(410);
        assertThat(call(get("/api/v1/reports/runs/" + id), fx.jwtFor(builder), null, 200).get("downloadable").asBoolean()).isFalse();
        assertThat(sweeper.expireFiles()).isGreaterThanOrEqualTo(1);
        assertThat(status(UUID.fromString(id))).isEqualTo("expired");
        assertThat(storage.resolve(location).exists(location, key)).as("the file is deleted from storage").isFalse();
        assertThat(download(builder, id).getStatus()).isEqualTo(410);
    }

    @Test
    void aRunStuckRunningAfterAProcessDiedIsFailedWithAPlainMessage_andARecentOneIsLeftAlone() throws Exception {
        UUID stuck = insertRun("running", "2 hours", builder, "owner", null);
        UUID recent = insertRun("running", "10 seconds", owner, "owner", null);

        assertThat(sweeper.failStuckRuns()).isGreaterThanOrEqualTo(1);

        assertThat(status(stuck)).isEqualTo("failed");
        assertThat(fx.jdbc().queryForObject("select error_code from report_runs where run_id = ?", String.class, stuck))
                .isEqualTo("REPORT_INTERRUPTED");
        assertThat(status(recent)).isEqualTo("running");
        assertThat(audits(stuck.toString(), "REPORT_FAILED")).isEqualTo(1);
    }

    @Test
    void oldRunHistoryIsDeleted_butARunWithAStoredFileIsNot() throws Exception {
        UUID old = insertRun("failed", null, builder, "owner", null);
        UUID withFile = insertRun("completed", null, builder, "owner", null);
        fx.jdbc().update("update report_runs set file_object_key = 'x' where run_id = ?", withFile);
        fx.jdbc().update("update report_runs set created_at = now() - interval '400 days' where run_id in (?, ?)", old, withFile);

        sweeper.deleteOldHistory();

        assertThat(fx.count("select count(*) from report_runs where run_id = ?", old)).isZero();
        assertThat(fx.count("select count(*) from report_runs where run_id = ?", withFile)).isEqualTo(1);
    }
}
