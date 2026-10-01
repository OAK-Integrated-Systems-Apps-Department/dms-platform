package com.oak.dms.importexport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V47 (one bulk import implementation: migration_* dropped, upload_sessions
 * linked from import_items) and V48 (export_jobs extended for generating
 * exports) on a scratch database - same harness as V42BulkImportMigrationTest.
 */
class V47V48ImportExportMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    @Test
    void v47_dropsTheDuplicateImportTables_keepsUploadSessions_andLinksThem() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "46");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "migration_batches")).isTrue();
            }
            migrate(url, "47");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "migration_batches")).isFalse();
                assertThat(tableExists(c, "migration_items")).isFalse();
                assertThat(tableExists(c, "upload_sessions")).isTrue();
                assertThat(strings(c, "select conname from pg_constraint where conrelid = 'import_items'::regclass"))
                        .contains("fk_import_items_upload_session");
            }
        });
    }

    @Test
    void v48_addsExportColumns_withDefaults_andChecksTheirValues() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "48");
            try (Connection c = connect(url)) {
                assertThat(strings(c, "select column_name from information_schema.columns where table_name = 'export_jobs'"))
                        .contains("metadata_format", "request_params", "document_count", "file_size_bytes",
                                "failure_reason", "started_at", "completed_at", "storage_location_id");

                String tenant = UUID.randomUUID().toString();
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                        + tenant + "', 'V48', 'v48-" + tenant + "', 'standard', 'active')");
                String user = UUID.randomUUID().toString();
                exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status) "
                        + "values ('" + user + "', '" + tenant + "', '" + UUID.randomUUID() + "', 'v48-" + user
                        + "@t.invalid', 'v48-" + user + "', 'V48', 'V48', 'active')");
                // A V41-shaped insert still works: every new column has a default.
                exec(c, "insert into export_jobs (tenant_id, requested_by, export_type) values ('" + tenant + "', '"
                        + user + "', 'bulk_selection')");
                assertThat(strings(c, "select metadata_format || '/' || request_params::text || '/' || document_count "
                        + "from export_jobs")).containsExactly("xlsx/{}/0");

                assertThatThrownBy(() -> exec(c, "insert into export_jobs (tenant_id, requested_by, export_type, "
                        + "metadata_format) values ('" + tenant + "', '" + user + "', 'bulk_selection', 'pdf')"))
                        .hasMessageContaining("ck_export_jobs_metadata_format");
                assertThatThrownBy(() -> exec(c, "insert into export_jobs (tenant_id, requested_by, export_type) "
                        + "values ('" + tenant + "', '" + user + "', 'everything')"))
                        .hasMessageContaining("ck_export_jobs_export_type");
            }
        });
    }

    @Test
    void v49_v50_onlyUsedFieCodesStayActive_andTemplateNamesAreUniquePerTypeAmongLiveOnes() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "50");
            try (Connection c = connect(url)) {
                assertThat(strings(c, "select permission_code from permissions where permission_code like 'fie.%' "
                        + "and tenant_id is null and not is_deprecated")).containsExactlyInAnyOrder(
                        "fie.bulk-import-documents.upload-file", "fie.bulk-export-documents.execute-export",
                        "fie.manage-import-templates.create-template", "fie.manage-import-templates.update-template",
                        "fie.manage-import-templates.delete-template");

                String tenant = UUID.randomUUID().toString();
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                        + tenant + "', 'V50', 'v50-" + tenant + "', 'standard', 'active')");
                String type = UUID.randomUUID().toString();
                exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('"
                        + type + "', '" + tenant + "', 'V50', 'V50 type')");
                String insert = "insert into import_templates (tenant_id, document_type_id, name, columns) values ('"
                        + tenant + "', '" + type + "', ";
                exec(c, insert + "'Finance', '[{\"fieldKey\": \"amount\", \"header\": \"Total\"}]')");
                assertThatThrownBy(() -> exec(c, insert + "'FINANCE', '[]')"))
                        .hasMessageContaining("uq_import_templates_live_name");
                exec(c, "update import_templates set is_deleted = true");
                exec(c, insert + "'Finance', '[]')"); // the name is free again once the old one is deleted
                assertThatThrownBy(() -> exec(c, insert + "'Bad', '{}')"))
                        .hasMessageContaining("ck_import_templates_columns_array");
                assertThat(strings(c, "select column_name from information_schema.columns where table_name = "
                        + "'import_batches' and column_name = 'import_template_id'")).hasSize(1);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        return !strings(c, "select table_name from information_schema.tables where table_schema = 'public' "
                + "and table_name = '" + table + "'").isEmpty();
    }

    private static Set<String> strings(Connection c, String sql) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, DB_USERNAME, DB_PASSWORD).locations("classpath:db/migration")
                .target(target).load().migrate();
    }

    @FunctionalInterface
    private interface ScratchTest {
        void run(String url) throws Exception;
    }

    private static void withScratchDatabase(ScratchTest test) throws Exception {
        String name = "dms_v48_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String base = DB_URL.substring(0, DB_URL.lastIndexOf('/') + 1);
        try (Connection admin = connect(base + "postgres"); Statement s = admin.createStatement()) {
            s.execute("create database " + name);
        }
        try {
            test.run(base + name);
        } finally {
            try (Connection admin = connect(base + "postgres"); Statement s = admin.createStatement()) {
                s.execute("drop database if exists " + name + " with (force)");
            }
        }
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, DB_USERNAME, DB_PASSWORD);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
