package com.oak.dms.importexport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * V42 (migration_batches / migration_items / upload_sessions) up, its key
 * constraints, and down via db/rollback - same scratch-database harness as
 * V41ExportJobsMigrationTest.
 */
class V42BulkImportMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String ROLLBACK_SCRIPT = "/db/rollback/V42__create_bulk_import.rollback.sql";
    private static final String[] TABLES = {"migration_batches", "migration_items", "upload_sessions"};

    @Test
    void up_createsTheThreeTablesWithTheirKeyConstraints() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "41");
            try (Connection c = connect(url)) {
                for (String t : TABLES) {
                    assertThat(tableExists(c, t)).as(t + " before V42").isFalse();
                }
            }
            migrate(url, "42");
            try (Connection c = connect(url)) {
                for (String t : TABLES) {
                    assertThat(tableExists(c, t)).as(t).isTrue();
                }
                assertThat(strings(c, "select conname from pg_constraint where conrelid = 'migration_batches'::regclass"))
                        .contains("fk_migration_batches_document_type", "ck_migration_batches_status",
                                "ck_migration_batches_template_format", "ck_migration_batches_counts");
                assertThat(strings(c, "select conname from pg_constraint where conrelid = 'migration_items'::regclass"))
                        .contains("fk_migration_items_batch", "fk_migration_items_document", "fk_migration_items_scan_result",
                                "ck_migration_items_success_has_document", "uq_migration_items_batch_row");
                assertThat(strings(c, "select indexname from pg_indexes where tablename = 'migration_batches'"))
                        .contains("uq_migration_batches_idempotency");

                String tenant = insert(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values "
                        + "(?, 'V42 tenant', 'v42-' || ?, 'standard', 'active')");
                String user = UUID.randomUUID().toString();
                exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status) values ('"
                        + user + "', '" + tenant + "', '" + UUID.randomUUID() + "', 'v42-" + user + "@t.invalid', 'v42-" + user
                        + "', 'V42', 'V42', 'active')");
                String type = UUID.randomUUID().toString();
                exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('"
                        + type + "', '" + tenant + "', 'V42', 'V42 type')");
                String batch = UUID.randomUUID().toString();
                exec(c, "insert into migration_batches (migration_batch_id, tenant_id, source_system, document_type_id, "
                        + "schema_version, template_format, total_records, created_by) values ('" + batch + "', '" + tenant
                        + "', 'bulk_import_template', '" + type + "', 1, 'xlsx', 2, '" + user + "')");
                // A success row must point at a document.
                assertThatThrownBy(() -> exec(c, "insert into migration_items (tenant_id, migration_batch_id, source_reference, "
                        + "status, row_number, row_data) values ('" + tenant + "', '" + batch + "', 'a.pdf', 'success', 2, '{}')"))
                        .hasMessageContaining("ck_migration_items_success_has_document");
                exec(c, "insert into migration_items (tenant_id, migration_batch_id, source_reference, row_number, row_data) values ('"
                        + tenant + "', '" + batch + "', 'a.pdf', 2, '{\"title\": \"A\"}')");
                // Same row number twice in one batch is refused.
                assertThatThrownBy(() -> exec(c, "insert into migration_items (tenant_id, migration_batch_id, source_reference, "
                        + "row_number, row_data) values ('" + tenant + "', '" + batch + "', 'b.pdf', 2, '{}')"))
                        .hasMessageContaining("uq_migration_items_batch_row");
                // Counts can never exceed the total.
                assertThatThrownBy(() -> exec(c, "update migration_batches set success_count = 3 where migration_batch_id = '" + batch + "'"))
                        .hasMessageContaining("ck_migration_batches_counts");
                // A resumable session can't claim more bytes than its size.
                assertThatThrownBy(() -> exec(c, "insert into upload_sessions (tenant_id, initiated_by, source, original_filename, "
                        + "total_size_bytes, received_bytes, expires_at) values ('" + tenant + "', '" + user
                        + "', 'bulk_import', 'big.zip', 10, 11, now())"))
                        .hasMessageContaining("ck_upload_sessions_bytes");
            }
        });
    }

    @Test
    void down_removesAllThreeTables_andUpCanBeReapplied() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "42");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                s.execute(rollbackSql());
                s.execute("delete from flyway_schema_history where version = '42'");
                for (String t : TABLES) {
                    assertThat(tableExists(c, t)).as(t + " after rollback").isFalse();
                }
                assertThat(tableExists(c, "export_jobs")).as("V41 untouched").isTrue();
            }
            migrate(url, "42");
            try (Connection c = connect(url)) {
                for (String t : TABLES) {
                    assertThat(tableExists(c, t)).as(t + " after re-applying V42").isTrue();
                }
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static String insert(Connection c, String sqlWithTwoIdPlaceholders) throws SQLException {
        String id = UUID.randomUUID().toString();
        exec(c, sqlWithTwoIdPlaceholders.replaceFirst("\\?", "'" + id + "'").replaceFirst("\\?", "'" + id + "'"));
        return id;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = '" + table + "'")) {
            rs.next();
            return rs.getLong(1) == 1;
        }
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

    private static String rollbackSql() throws Exception {
        try (InputStream in = V42BulkImportMigrationTest.class.getResourceAsStream(ROLLBACK_SCRIPT)) {
            assertThat(in).as(ROLLBACK_SCRIPT + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
        String name = "dms_v42_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
