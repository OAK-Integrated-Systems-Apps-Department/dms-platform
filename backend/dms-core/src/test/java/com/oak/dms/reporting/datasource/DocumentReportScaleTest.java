package com.oak.dms.reporting.datasource;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderAccessView;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.ReportFieldCatalog;
import com.oak.dms.reporting.datasource.DocumentReportQueryBuilder.Limits;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ReportDefinition.Filter;
import com.oak.dms.reporting.dto.ReportDefinition.Sort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a preview costs at 100,000 documents: the count and the first 50 rows, for the access and filter shapes that matter.
 * Each statement must finish inside the preview timeout (10 s default); the plans and timings are written to
 * {@code target/crg-explain.txt} so an index decision is made from a plan, not a guess.
 */
class DocumentReportScaleTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final long BUDGET_MS = 10_000;
    private static final int DOCUMENTS = 100_000;

    private static final UUID TENANT = UUID.randomUUID();
    private static final MetadataField VALUE = MetadataField.builder().fieldId(UUID.randomUUID()).tenantId(TENANT)
            .fieldKey("contractValue").label("contractValue").dataType("decimal").searchable(true).build();
    private static final EffectiveCatalogue CATALOGUE = ReportFieldCatalog.merge(List.of(VALUE), List.of());

    private static String scratch;
    private static String base;
    private static SingleConnectionDataSource dataSource;
    private static NamedParameterJdbcTemplate jdbc;
    private static Set<UUID> grantedRestricted;
    private static UUID typeA;
    private static UUID userA;
    private static final StringBuilder REPORT = new StringBuilder();

    private final DocumentReportQueryBuilder builder = new DocumentReportQueryBuilder();

    @BeforeAll
    static void loadHundredThousandDocuments() throws Exception {
        scratch = "dms_crg_scale_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        base = DB_URL.substring(0, DB_URL.lastIndexOf('/') + 1);
        try (Connection admin = DriverManager.getConnection(base + "postgres", DB_USERNAME, DB_PASSWORD);
             Statement s = admin.createStatement()) {
            s.execute("create database " + scratch);
        }
        Flyway.configure().dataSource(base + scratch, DB_USERNAME, DB_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        dataSource = new SingleConnectionDataSource(base + scratch, DB_USERNAME, DB_PASSWORD, true);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        var t = jdbc.getJdbcTemplate();
        t.execute("set session_replication_role = replica");

        String tenant = TENANT.toString();
        t.update("insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values (?, 'Scale', ?, 'standard', 'active')",
                TENANT, "scale-" + TENANT);
        t.execute("create temp table gen_users as select gen_random_uuid() as id, g from generate_series(1, 200) g");
        t.execute("insert into users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username) "
                + "select id, '" + tenant + "', gen_random_uuid(), 'u' || g || '@example.com', 'User ' || g, 'User', 'N' || g, 'user' || g from gen_users");
        t.execute("create temp table gen_types as select gen_random_uuid() as id, g from generate_series(1, 20) g");
        t.execute("insert into document_types (document_type_id, tenant_id, type_code, type_name) select id, '" + tenant
                + "', 'T' || g, 'Type ' || g from gen_types");
        t.execute("create temp table gen_depts as select gen_random_uuid() as id, g from generate_series(1, 20) g");
        t.execute("insert into departments (department_id, tenant_id, code, name) select id, '" + tenant
                + "', 'D' || g, 'Dept ' || g from gen_depts");
        t.execute("create temp table gen_folders as select gen_random_uuid() as id, g from generate_series(1, 50) g");
        t.execute("insert into folders (folder_id, tenant_id, folder_name, access_mode) select id, '" + tenant
                + "', 'Folder ' || g, case when g <= 5 then 'restricted' else 'inherit' end from gen_folders");
        // type 2 / user 2: the generator archives every 20th document, which is all of type 1 and user 1's
        typeA = t.queryForObject("select id from gen_types where g = 2", UUID.class);
        userA = t.queryForObject("select id from gen_users where g = 2", UUID.class);

        t.execute("create temp table gen as select gen_random_uuid() as doc_id, gen_random_uuid() as ver_id, g "
                + "from generate_series(1, " + DOCUMENTS + ") g");
        // Joins on the small lookup tables (not correlated subqueries, where an inner "g" would shadow the outer one).
        t.execute("insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, current_version_id, "
                + "owner_user_id, created_by, created_at, access_mode, folder_id, owner_department_id, lifecycle_state) "
                + "select o.doc_id, '" + tenant + "', ty.id, 'Document ' || o.g || ' ' || md5(o.g::text), o.ver_id, o.ver_id, "
                + "u.id, u.id, now() - (o.g % 730) * interval '1 day', "
                + "case when o.g % 100 = 0 then 'restricted' else 'inherit' end, "
                + "case when o.g % 7 = 0 then null else fo.id end, dp.id, "
                + "case when o.g % 20 = 0 then 'archived' else 'active' end "
                + "from gen o join gen_types ty on ty.g = 1 + o.g % 20 join gen_users u on u.g = 1 + o.g % 200 "
                + "join gen_depts dp on dp.g = 1 + o.g % 20 join gen_folders fo on fo.g = 1 + o.g % 50");
        t.execute("insert into document_versions (version_id, document_id, tenant_id, version_number, major_version, minor_version, "
                + "storage_location_id, storage_object_key, original_filename, mime_type, file_hash, size_bytes, created_reason, page_count) "
                + "select ver_id, doc_id, '" + tenant + "', 1, 1, 0, gen_random_uuid(), 'k' || g, 'file' || g || '.' || (array['pdf','docx','xlsx','png','txt'])[1 + g % 5], "
                + "'application/octet-stream', 'h', 1000 + g, 'initial_upload', g % 50 from gen");
        t.execute("insert into document_metadata (tenant_id, document_id, document_type_id, schema_version, field_values) "
                + "select '" + tenant + "', doc_id, (select id from gen_types where g = 1), 1, "
                + "jsonb_build_object('" + VALUE.getFieldId() + "', jsonb_build_object('v', g % 1000)) from gen where g % 2 = 0");
        t.execute("insert into workflow_instances (tenant_id, document_id, document_version_id, template_id, template_version_number, status, "
                + "initiated_by, started_at, completed_at) select '" + tenant + "', doc_id, ver_id, gen_random_uuid(), 1, "
                + "(array['approved','rejected','cancelled'])[1 + g % 3], (select id from gen_users where g = 1), now() - interval '10 days', now() - interval '9 days' "
                + "from gen where g % 5 = 0");
        t.execute("insert into workflow_instances (tenant_id, document_id, document_version_id, template_id, template_version_number, status, "
                + "initiated_by, started_at) select '" + tenant + "', doc_id, ver_id, gen_random_uuid(), 1, 'in_progress', "
                + "(select id from gen_users where g = 1), now() - interval '1 day' from gen where g % 25 = 1");
        for (String table : List.of("documents", "document_versions", "document_metadata", "workflow_instances", "users", "folders")) {
            t.execute("analyze " + table);
        }

        grantedRestricted = new HashSet<>(t.queryForList(
                "select doc_id from gen where g % 100 = 0 and g % 200 = 0 limit 50", UUID.class));
    }

    @AfterAll
    static void dropAndWriteReport() throws Exception {
        Path out = Path.of("target", "crg-explain.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, REPORT.toString());
        if (dataSource != null) {
            dataSource.destroy();
        }
        try (Connection admin = DriverManager.getConnection(base + "postgres", DB_USERNAME, DB_PASSWORD);
             Statement s = admin.createStatement()) {
            s.execute("drop database if exists " + scratch + " with (force)");
        }
    }

    private static DocumentAccessView admin() {
        return new DocumentAccessView(new FolderAccessView(List.of(), Map.of(), true), Map.of(), true, true);
    }

    /** Five folders hidden (those 5 of 50 are restricted with no grant), 1% of documents restricted, 50 of them granted. */
    private static DocumentAccessView limited() {
        List<Folder> folders = new ArrayList<>();
        jdbc.getJdbcTemplate().query("select folder_id, access_mode from folders where tenant_id = ?", rs -> {
            folders.add(Folder.builder().folderId(rs.getObject(1, UUID.class)).tenantId(TENANT).folderName("f")
                    .accessMode(FolderAccessMode.valueOf(rs.getString(2))).build());
        }, TENANT);
        Map<UUID, FolderAccessLevel> granted = new java.util.HashMap<>();
        grantedRestricted.forEach(id -> granted.put(id, FolderAccessLevel.view));
        return new DocumentAccessView(new FolderAccessView(folders, Map.of(), false), granted, false, true, Set.of());
    }

    private static Filter f(String field, String op, String... values) {
        return new Filter(field, op, List.of(values));
    }

    private static final List<String> COLUMNS =List.of("title", "documentType", "owner", "createdAt", "status");

    private void measure(String name, DocumentAccessView access, ReportDefinition definition) {
        ReportQuery q = builder.build(TENANT, access, definition, CATALOGUE, new Limits(25, 40, 5), ZoneId.of("UTC"),
                Instant.now(), false);
        MapSqlParameterSource params = new MapSqlParameterSource(q.params());

        long t0 = System.nanoTime();
        Long total = jdbc.queryForObject(q.countSql(), params, Long.class);
        long countMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        int sample = jdbc.queryForList(q.sql() + " limit 50", params).size();
        long previewMs = (System.nanoTime() - t0) / 1_000_000;

        REPORT.append("=== ").append(name).append("  total=").append(total).append(" sample=").append(sample)
                .append("  count=").append(countMs).append("ms preview=").append(previewMs).append("ms\n");
        REPORT.append("-- count plan\n").append(plan("explain (analyze, buffers, costs off) " + q.countSql(), params));
        REPORT.append("-- preview plan\n").append(plan("explain (analyze, buffers, costs off) " + q.sql() + " limit 50", params));
        assertThat(countMs).as(name + " count").isLessThan(BUDGET_MS);
        assertThat(previewMs).as(name + " preview").isLessThan(BUDGET_MS);
        assertThat(sample).isLessThanOrEqualTo(50);
    }

    private static String plan(String sql, MapSqlParameterSource params) {
        StringBuilder sb = new StringBuilder();
        jdbc.query(sql, params, rs -> {
            sb.append(rs.getString(1)).append('\n');
        });
        return sb.toString();
    }

    private static ReportDefinition def(List<Sort> sort, Filter... filters) {
        return new ReportDefinition("documents", List.of(filters), COLUMNS, sort, null, null, null);
    }

    @Test
    void anUnfilteredReport_forAnAdministrator_andForALimitedUser() {
        measure("admin, newest first", admin(), def(List.of(new Sort("createdAt", "desc"))));
        measure("limited, newest first", limited(), def(List.of(new Sort("createdAt", "desc"))));
        measure("admin, by title", admin(), def(List.of(new Sort("title", "asc"))));
        measure("admin, by owner name", admin(), def(List.of(new Sort("owner", "asc"))));
    }

    @Test
    void commonFilters() {
        measure("document type + last 30 days", limited(), def(List.of(new Sort("createdAt", "desc")),
                f("documentType", "is_any_of", typeA.toString()), f("createdAt", "relative", "last_30_days")));
        measure("owner", limited(), def(List.of(new Sort("createdAt", "desc")),
                f("owner", "is_any_of", userA.toString())));
        measure("legal hold", limited(), def(List.of(new Sort("createdAt", "desc")), f("legalHold", "is_true")));
        measure("file type pdf", limited(), def(List.of(new Sort("createdAt", "desc")), f("fileType", "equals", "pdf")));
    }

    @Test
    void theExpensiveOnes() {
        measure("title contains (no index can help)", limited(), def(List.of(new Sort("title", "asc")), f("title", "contains", "abc")));
        measure("custom number field", limited(), def(List.of(new Sort("createdAt", "desc")),
                f("meta.contractValue", "gt", "900")));
        measure("approval status", limited(), def(List.of(new Sort("createdAt", "desc")),
                f("approvalStatus", "is_any_of", "in_progress")));
        measure("approval status, not submitted", limited(), def(List.of(new Sort("createdAt", "desc")),
                f("approvalStatus", "is_any_of", "not_submitted")));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
