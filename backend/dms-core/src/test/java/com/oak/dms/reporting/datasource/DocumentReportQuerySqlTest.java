package com.oak.dms.reporting.datasource;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderAccessView;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.catalog.ReportFieldCatalog;
import com.oak.dms.reporting.datasource.DocumentReportQueryBuilder.Limits;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ReportDefinition.Filter;
import com.oak.dms.reporting.dto.ReportDefinition.Sort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs what {@link DocumentReportQueryBuilder} produces against real PostgreSQL on a scratch database migrated to the latest
 * version: the SQL is valid (named-parameter parsing, JSON operators, the date regex), every join is a left join, the access
 * predicate really hides rows - from the rows, the count and the group counts alike - and a malformed metadata value cannot fail
 * a report. Same harness as {@code V138CustomReportsMigrationTest}; foreign keys are bypassed for fixtures only
 * ({@code session_replication_role = replica}), the queries themselves run normally.
 */
class DocumentReportQuerySqlTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final Limits LIMITS = new Limits(25, 40, 5);
    private static final Instant NOW = Instant.parse("2024-03-10T12:00:00Z");

    private static String scratch;
    private static String base;
    private static NamedParameterJdbcTemplate jdbc;
    private static SingleConnectionDataSource dataSource;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();
    private static final UUID DEPT = UUID.randomUUID();
    private static final UUID F_OPEN = UUID.randomUUID();
    private static final UUID F_HR = UUID.randomUUID();
    private static final UUID STAGE = UUID.randomUUID();

    private static final Map<String, UUID> DOC = new LinkedHashMap<>();
    private static final MetadataField VALUE = meta("contractValue", "decimal");
    private static final MetadataField SIGNED = meta("signedOn", "date");
    private static final MetadataField CRITICAL = meta("isCritical", "boolean");
    private static final MetadataField REGION = meta("region", "dropdown");
    private static final MetadataField TAGS = meta("tags", "multi_select");

    private static final EffectiveCatalogue CATALOGUE =
            ReportFieldCatalog.merge(List.of(VALUE, SIGNED, CRITICAL, REGION, TAGS), List.of());

    private final DocumentReportQueryBuilder builder = new DocumentReportQueryBuilder();

    // ------------------------------------------------------------------ fixtures

    @BeforeAll
    static void createScratchDatabaseAndFixtures() throws Exception {
        scratch = "dms_crg_sql_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        base = DB_URL.substring(0, DB_URL.lastIndexOf('/') + 1);
        try (Connection admin = connect(base + "postgres"); Statement s = admin.createStatement()) {
            s.execute("create database " + scratch);
        }
        Flyway.configure().dataSource(base + scratch, DB_USERNAME, DB_PASSWORD)
                .locations("classpath:db/migration").load().migrate();

        dataSource = new SingleConnectionDataSource(base + scratch, DB_USERNAME, DB_PASSWORD, true);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("set session_replication_role = replica");

        insert("tenants", Map.of("tenant_id", TENANT, "tenant_name", "CRG", "domain", "crg-" + TENANT,
                "subscription_tier", "standard", "status", "active"));
        for (UUID user : List.of(ALICE, BOB)) {
            String name = user == ALICE ? "Alice" : "Bob";
            insert("users", Map.of("user_id", user, "tenant_id", TENANT, "keycloak_id", UUID.randomUUID(),
                    "email", name + "@example.com", "display_name", name, "first_name", name, "last_name", "Tester",
                    "username", name + "-" + user.toString().substring(0, 8)));
        }
        insert("document_types", Map.of("document_type_id", TYPE, "tenant_id", TENANT, "type_name", "Contract"));
        insert("departments", Map.of("department_id", DEPT, "tenant_id", TENANT, "name", "Legal"));
        insert("folders", Map.of("folder_id", F_OPEN, "tenant_id", TENANT, "folder_name", "Open", "access_mode", "inherit"));
        insert("folders", Map.of("folder_id", F_HR, "tenant_id", TENANT, "folder_name", "HR", "access_mode", "restricted"));

        doc("alpha", "Alpha contract", F_OPEN, "inherit", Map.of("owner_department_id", DEPT, "legal_hold", true,
                "legal_hold_at", OffsetDateTime.parse("2024-03-09T11:00:00Z"), // the CHECK ties the flag to its timestamp
                "created_at", OffsetDateTime.parse("2024-03-09T10:00:00Z")), 10, 1000, "alpha.PDF");
        doc("beta", "Beta in HR", F_HR, "inherit", Map.of(), 2, 200, "beta.docx");
        doc("gamma", "Gamma restricted", F_OPEN, "restricted", Map.of(), 3, 300, "gamma.xlsx");
        doc("delta", "Delta granted", F_OPEN, "restricted", Map.of(), 4, 400, "delta");
        doc("epsilon", "Epsilon approval", F_HR, "inherit", Map.of(), 5, 500, "epsilon.pdf");
        doc("template", "A template", F_OPEN, "inherit", Map.of("is_template", true), 1, 1, "t.docx");
        doc("binned", "In the bin", F_OPEN, "inherit", Map.of("is_deleted", true), 1, 1, "b.docx");
        doc("archived", "Archived one", F_OPEN, "inherit", Map.of("lifecycle_state", "archived"), 1, 1, "a.docx");
        doc("purged", "Purged tombstone", F_OPEN, "inherit", Map.of("purged_at", OffsetDateTime.now()), 1, 1, "p.docx");
        // no folder, no department, and a document type that does not exist: must still be listed
        doc("orphan", "Orphan", null, "inherit", Map.of("document_type_id", UUID.randomUUID()), null, 7, "orphan.txt");

        metadata("alpha", "{\"%s\":{\"v\":100.5},\"%s\":{\"v\":\"2024-01-15\"},\"%s\":{\"v\":true},\"%s\":{\"v\":\"EU\"},\"%s\":{\"v\":[\"red\",\"blue\"]}}"
                .formatted(VALUE.getFieldId(), SIGNED.getFieldId(), CRITICAL.getFieldId(), REGION.getFieldId(), TAGS.getFieldId()));
        // dirty data a report must survive
        metadata("orphan", "{\"%s\":{\"v\":\"abc\"},\"%s\":{\"v\":\"garbage\"},\"%s\":{\"v\":\"yes\"},\"%s\":{\"v\":7}}"
                .formatted(VALUE.getFieldId(), SIGNED.getFieldId(), CRITICAL.getFieldId(), TAGS.getFieldId()));

        // alpha: an older rejected run, then the latest, approved. epsilon: running, at "Legal Review".
        workflow("alpha", "rejected", "2024-01-01T00:00:00Z", null);
        workflow("alpha", "approved", "2024-02-01T00:00:00Z", null);
        insert("workflow_stages", Map.of("stage_id", STAGE, "tenant_id", TENANT, "name", "Legal Review",
                "completion_rule", "all", "rejection_rule", "halt_immediately"));
        UUID stageInstance = UUID.randomUUID();
        insert("workflow_stage_instances", Map.of("stage_instance_id", stageInstance, "tenant_id", TENANT,
                "instance_id", UUID.randomUUID(), "stage_id", STAGE, "stage_order", 1, "status", "active"));
        workflow("epsilon", "in_progress", "2024-03-01T00:00:00Z", stageInstance);
    }

    @AfterAll
    static void dropScratchDatabase() throws Exception {
        if (dataSource != null) {
            dataSource.destroy();
        }
        try (Connection admin = connect(base + "postgres"); Statement s = admin.createStatement()) {
            s.execute("drop database if exists " + scratch + " with (force)");
        }
    }

    private static void doc(String key, String title, UUID folder, String accessMode, Map<String, Object> extra,
                            Integer pages, long size, String filename) {
        UUID id = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        DOC.put(key, id);
        Map<String, Object> d = new LinkedHashMap<>(Map.of("document_id", id, "tenant_id", TENANT,
                "document_type_id", TYPE, "title", title, "latest_version_id", version, "current_version_id", version,
                "owner_user_id", ALICE, "created_by", ALICE, "access_mode", accessMode));
        if (folder != null) {
            d.put("folder_id", folder);
        }
        d.putAll(extra);
        insert("documents", d);
        Map<String, Object> v = new LinkedHashMap<>(Map.of("version_id", version, "document_id", id, "tenant_id", TENANT,
                "version_number", 3, "original_filename", filename, "size_bytes", size, "created_reason", "initial_upload"));
        if (pages != null) {
            v.put("page_count", pages);
        }
        insert("document_versions", v);
    }

    private static void metadata(String doc, String json) {
        insert("document_metadata", Map.of("tenant_id", TENANT, "document_id", DOC.get(doc),
                "document_type_id", TYPE, "schema_version", 1, "field_values", new Json(json)));
    }

    private static void workflow(String doc, String status, String startedAt, UUID stageInstance) {
        Map<String, Object> w = new LinkedHashMap<>(Map.of("tenant_id", TENANT, "document_id", DOC.get(doc),
                "status", status, "started_at", OffsetDateTime.parse(startedAt), "initiated_by", ALICE));
        if (stageInstance != null) {
            w.put("current_stage_instance_id", stageInstance);
        }
        if (!"in_progress".equals(status)) {
            w.put("completed_at", OffsetDateTime.parse(startedAt).plusDays(1)); // ck_workflow_instances_completed
        }
        insert("workflow_instances", w);
    }

    private record Json(String text) {
    }

    /** Inserts one row, filling every other NOT NULL column that has no default with a harmless value of its type. */
    private static void insert(String table, Map<String, Object> values) {
        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            columns.add(e.getKey());
            if (e.getValue() instanceof Json j) {
                placeholders.add("cast(? as jsonb)");
                binds.add(j.text());
            } else {
                placeholders.add("?");
                binds.add(e.getValue());
            }
        }
        jdbc.getJdbcTemplate().query("""
                select column_name, data_type from information_schema.columns
                where table_schema = 'public' and table_name = ? and is_nullable = 'NO' and column_default is null
                  and is_generated = 'NEVER' and is_identity = 'NO'""", rs -> {
            String column = rs.getString(1);
            if (values.containsKey(column)) {
                return;
            }
            columns.add(column);
            placeholders.add(switch (rs.getString(2)) {
                case "uuid" -> "gen_random_uuid()";
                case "integer", "bigint", "smallint", "numeric" -> "1";
                case "boolean" -> "false";
                case "timestamp with time zone" -> "now()";
                case "jsonb", "json" -> "'{}'::jsonb";
                case "date" -> "current_date";
                default -> "'x'";
            });
        }, table);
        String sql = "insert into " + table + " (" + String.join(", ", columns) + ") values (" + String.join(", ", placeholders) + ")";
        jdbc.getJdbcTemplate().update(sql, binds.toArray());
    }

    // ------------------------------------------------------------------ helpers

    private static MetadataField meta(String key, String type) {
        return MetadataField.builder().fieldId(UUID.randomUUID()).tenantId(TENANT).fieldKey(key).label(key)
                .dataType(type).searchable(true).build();
    }

    private static DocumentAccessView admin() {
        return new DocumentAccessView(new FolderAccessView(List.of(), Map.of(), true), Map.of(), true, true);
    }

    /** Bob: HR folder hidden, the restricted document "delta" granted, and "epsilon" readable through a running approval. */
    private static DocumentAccessView bob() {
        Folder hr = Folder.builder().folderId(F_HR).tenantId(TENANT).folderName("HR").accessMode(FolderAccessMode.restricted).build();
        Folder open = Folder.builder().folderId(F_OPEN).tenantId(TENANT).folderName("Open").accessMode(FolderAccessMode.inherit).build();
        return new DocumentAccessView(new FolderAccessView(List.of(hr, open), Map.of(), false),
                Map.of(DOC.get("delta"), FolderAccessLevel.view), false, true, Set.of(DOC.get("epsilon")));
    }

    private static Filter f(String field, String op, String... values) {
        return new Filter(field, op, List.of(values));
    }

    private ReportQuery build(DocumentAccessView access, ReportDefinition d) {
        return builder.build(TENANT, access, d, CATALOGUE, LIMITS, ZoneId.of("UTC"), NOW, false);
    }

    private static ReportDefinition def(List<String> columns, Filter... filters) {
        return new ReportDefinition("documents", List.of(filters), columns, List.of(new Sort("title", "asc")), null, null, null);
    }

    private static List<Map<String, Object>> rows(ReportQuery q) {
        return jdbc.queryForList(q.sql(), q.params());
    }

    private static long count(ReportQuery q) {
        return jdbc.queryForObject(q.countSql(), q.params(), Long.class);
    }

    private List<String> titles(DocumentAccessView access, Filter... filters) {
        ReportQuery q = build(access, def(List.of("title"), filters));
        List<String> titles = rows(q).stream().map(r -> (String) r.get("c0")).toList();
        assertThat(count(q)).as("the count agrees with the rows").isEqualTo(titles.size());
        return titles;
    }

    // ------------------------------------------------------------------ access

    @Test
    void anAdministratorSeesEveryLiveDocument_butNeverTemplatesBinnedOrPurgedOnes_andNotArchivedByDefault() {
        assertThat(titles(admin())).containsExactly("Alpha contract", "Beta in HR", "Delta granted", "Epsilon approval",
                "Gamma restricted", "Orphan");
    }

    @Test
    void archivedDocumentsAppearOnlyWhenAskedFor() {
        ReportDefinition d = new ReportDefinition("documents", List.of(), List.of("title"), List.of(new Sort("title", "asc")),
                null, true, null);

        assertThat(rows(build(admin(), d)).stream().map(r -> r.get("c0")).toList()).contains("Archived one")
                .doesNotContain("A template", "In the bin", "Purged tombstone");
    }

    @Test
    void aLimitedUserGetsOnlyWhatTheyMaySee_inTheRowsAndInTheTotal() {
        ReportQuery q = build(bob(), def(List.of("title")));

        // hidden: the HR folder's document, and the restricted one they are not on. Visible: open + their grant +
        // the document a running approval lets them read.
        assertThat(rows(q).stream().map(r -> r.get("c0")).toList())
                .containsExactly("Alpha contract", "Delta granted", "Epsilon approval", "Orphan");
        assertThat(count(q)).isEqualTo(4);
        assertThat(q.limitedToAccess()).isTrue();
    }

    @Test
    void groupCountsAndFilteredTotalsNeverCountARowTheCallerCannotSee() {
        ReportDefinition grouped = new ReportDefinition("documents", List.of(), List.of("title"), List.of(), "status", null, null);
        ReportQuery q = build(bob(), grouped);

        long grouped_total = jdbc.queryForList(q.groupCountSql(), q.params()).stream()
                .mapToLong(r -> ((Number) r.get("n")).longValue()).sum();

        assertThat(grouped_total).isEqualTo(4);
        assertThat(count(build(bob(), def(List.of("title"), f("title", "contains", "e"))))).isEqualTo(
                titles(bob(), f("title", "contains", "e")).size());
    }

    @Test
    void theApprovalReadDisappearsWhenTheApprovalIsNoLongerRunning() {
        DocumentAccessView noApproval = new DocumentAccessView(bob().folders(), bob().granted(), false, true, Set.of());

        assertThat(titles(noApproval)).containsExactly("Alpha contract", "Delta granted", "Orphan");
    }

    // ------------------------------------------------------------------ derived and joined fields

    @Test
    void approvalStatusComesFromTheLatestWorkflowInstance_andStageOnlyWhileItRuns() {
        ReportQuery q = build(admin(), def(List.of("title", "approvalStatus", "workflowStage")));
        Map<String, List<Object>> byTitle = rows(q).stream().collect(Collectors.toMap(r -> (String) r.get("c0"),
                r -> java.util.Arrays.asList(r.get("c1"), r.get("c2"))));

        assertThat(byTitle.get("Alpha contract")).containsExactly("Approved", null); // latest of two instances
        assertThat(byTitle.get("Epsilon approval")).containsExactly("In approval", "Legal Review");
        assertThat(byTitle.get("Beta in HR")).containsExactly("Not submitted", null);
    }

    @Test
    void filtersOnApprovalStatusAndStage() {
        assertThat(titles(admin(), f("approvalStatus", "is_any_of", "approved"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("approvalStatus", "is_any_of", "not_submitted")))
                .containsExactly("Beta in HR", "Delta granted", "Gamma restricted", "Orphan");
        assertThat(titles(admin(), f("workflowStage", "is_any_of", "legal review"))).containsExactly("Epsilon approval");
        assertThat(titles(admin(), f("workflowStage", "is_any_of", STAGE.toString()))).containsExactly("Epsilon approval");
        assertThat(titles(admin(), f("workflowStage", "is_none_of", "Legal Review")))
                .doesNotContain("Epsilon approval").contains("Alpha contract");
    }

    @Test
    void aDocumentWithNoFolderDepartmentOrKnownTypeStillAppears_withEmptyCells() {
        ReportQuery q = build(admin(), def(List.of("title", "documentType", "department", "folderPath", "pageCount"),
                f("title", "equals", "orphan")));

        Map<String, Object> row = rows(q).get(0);

        assertThat(row.get("c1")).isNull();
        assertThat(row.get("c2")).isNull();
        assertThat(row.get("c3")).isNull();
        assertThat(row.get("c4")).isNull();
    }

    @Test
    void fileTypeIsTheLowerCasedExtension_andEmptyWhenThereIsNone() {
        assertThat(titles(admin(), f("fileType", "equals", "pdf"))).containsExactly("Alpha contract", "Epsilon approval");
        assertThat(titles(admin(), f("fileType", "is_empty"))).containsExactly("Delta granted");
    }

    @Test
    void numberDateAndBooleanBuiltInFilters() {
        assertThat(titles(admin(), f("pageCount", "gte", "5"))).containsExactly("Alpha contract", "Epsilon approval");
        assertThat(titles(admin(), f("fileSize", "between", "200", "300"))).containsExactly("Beta in HR", "Gamma restricted");
        assertThat(titles(admin(), f("legalHold", "is_true"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("createdAt", "on", "2024-03-09"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("department", "is_any_of", DEPT.toString()))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("owner", "is_any_of", BOB.toString()))).isEmpty();
        assertThat(titles(admin(), f("title", "contains", "%"))).as("a percent sign is literal").isEmpty();
    }

    // ------------------------------------------------------------------ custom metadata

    @Test
    void metadataFiltersWork_andMalformedStoredValuesAreSkippedNotFatal() {
        assertThat(titles(admin(), f("meta.contractValue", "gt", "50"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("meta.signedOn", "before", "2024-02-01"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("meta.signedOn", "between", "2024-01-01", "2024-01-31"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("meta.isCritical", "is_true"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("meta.region", "is_any_of", "EU"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("meta.region", "is_none_of", "EU"))).doesNotContain("Alpha contract").contains("Beta in HR");
        assertThat(titles(admin(), f("meta.tags", "is_any_of", "blue"))).containsExactly("Alpha contract");
        assertThat(titles(admin(), f("meta.tags", "is_none_of", "blue"))).doesNotContain("Alpha contract");
        // orphan stored tags as a number - not a list: counts as empty, never an error
        assertThat(titles(admin(), f("meta.tags", "is_empty"))).contains("Orphan").doesNotContain("Alpha contract");
    }

    @Test
    void metadataColumnsAreTypedValues() {
        ReportQuery q = build(admin(), def(List.of("title", "meta.contractValue", "meta.signedOn", "meta.isCritical"),
                f("title", "equals", "alpha contract")));

        Map<String, Object> row = rows(q).get(0);

        assertThat(((Number) row.get("c1")).doubleValue()).isEqualTo(100.5);
        assertThat(row.get("c2").toString()).isEqualTo("2024-01-15");
        assertThat(row.get("c3")).isEqualTo(true);
    }

    // ------------------------------------------------------------------ everything at once

    @Test
    void everyBuiltInFieldCanBeSelectedFilteredSortedAndGroupedWithoutAnSqlError() {
        List<String> all = CATALOGUE.all().stream().filter(FieldDescriptor::selectable).map(FieldDescriptor::key).toList();
        List<Sort> sorts = CATALOGUE.all().stream().filter(FieldDescriptor::sortable).map(f -> new Sort(f.key(), "desc")).toList();

        for (FieldDescriptor field : CATALOGUE.all()) {
            if (field.sortable()) {
                ReportQuery q = build(admin(), new ReportDefinition("documents", List.of(), all, List.of(new Sort(field.key(), "asc")),
                        null, true, null));
                assertThat(rows(q)).as("sorted by " + field.key()).isNotEmpty();
            }
            if (field.groupable()) {
                ReportQuery q = build(admin(), new ReportDefinition("documents", List.of(), List.of("title"), List.of(),
                        field.key(), true, null));
                assertThat(rows(q)).as("grouped by " + field.key()).isNotEmpty();
                assertThat(jdbc.queryForList(q.groupCountSql(), q.params())).as("group counts " + field.key()).isNotEmpty();
            }
        }
        assertThat(rows(build(admin(), new ReportDefinition("documents", List.of(), all, sorts.subList(0, 3), null, true, null))))
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ harness

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, DB_USERNAME, DB_PASSWORD);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
