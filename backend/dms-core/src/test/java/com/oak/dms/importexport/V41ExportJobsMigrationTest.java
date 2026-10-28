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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V41 (export_jobs) up and down, on a throwaway database - the same harness
 * as V36MigrationIT / V37MigrationIT. "Down" is the manual rollback script in
 * db/rollback (Flyway Community has no undo migrations), followed by removing
 * V41 from flyway_schema_history; re-migrating afterwards proves the pair is
 * repeatable. Named *Test (not *IT) so CI runs it: it only needs the Postgres
 * that CI already provides, with permission to create databases.
 */
class V41ExportJobsMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String ROLLBACK_SCRIPT = "/db/rollback/V41__create_export_jobs.rollback.sql";

    @Test
    void up_createsTableColumnsConstraintsAndIndexes() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "40");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c)).as("export_jobs before V41").isFalse();
            }

            migrate(url, "41");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c)).isTrue();

                Map<String, String> nullable = columnNullability(c);
                assertThat(nullable).containsOnlyKeys("export_job_id", "tenant_id", "requested_by", "export_type",
                        "status", "file_object_key", "expires_at", "correlation_id", "created_at", "updated_at");
                for (String required : new String[] {"export_job_id", "tenant_id", "requested_by", "export_type",
                        "status", "created_at", "updated_at"}) {
                    assertThat(nullable.get(required)).as(required + " nullable").isEqualTo("NO");
                }
                for (String optional : new String[] {"file_object_key", "expires_at", "correlation_id"}) {
                    assertThat(nullable.get(optional)).as(optional + " nullable").isEqualTo("YES");
                }
                assertThat(dataType(c, "created_at")).isEqualTo("timestamp with time zone");
                assertThat(dataType(c, "expires_at")).isEqualTo("timestamp with time zone");
                assertThat(dataType(c, "correlation_id")).isEqualTo("uuid");

                assertThat(constraints(c)).contains("ck_export_jobs_export_type", "ck_export_jobs_status",
                        "export_jobs_tenant_id_fkey", "export_jobs_requested_by_fkey");
                assertThat(foreignKeyTargets(c)).contains("tenant_id->tenants.tenant_id", "requested_by->users.user_id");
                assertThat(indexes(c)).contains("ix_export_jobs_tenant_id", "ix_export_jobs_status", "ix_export_jobs_expires_at");

                // Server-side defaults: id, status and both timestamps.
                String tenant = insertTenant(c);
                String user = insertUser(c, tenant);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                        "insert into export_jobs (tenant_id, requested_by, export_type) values ('" + tenant + "', '"
                                + user + "', 'bulk_folder') returning export_job_id, status, created_at, updated_at")) {
                    rs.next();
                    assertThat(rs.getObject("export_job_id")).isNotNull();
                    assertThat(rs.getString("status")).isEqualTo("pending");
                    assertThat(rs.getTimestamp("created_at")).isNotNull();
                    assertThat(rs.getTimestamp("updated_at")).isNotNull();
                }
            }
        });
    }

    @Test
    void down_removesTheTableCleanly_andUpCanBeReapplied() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "41");

            try (Connection c = connect(url); Statement s = c.createStatement()) {
                s.execute(rollbackSql());
                s.execute("delete from flyway_schema_history where version = '41'");
                assertThat(tableExists(c)).as("export_jobs after rollback").isFalse();
                assertThat(indexes(c)).doesNotContain("ix_export_jobs_tenant_id", "ix_export_jobs_status",
                        "ix_export_jobs_expires_at");
                // Nothing V41 depends on was touched.
                assertThat(count(c, "select count(*) from information_schema.tables where table_name in ('tenants', 'users')"))
                        .isEqualTo(2);
            }

            migrate(url, "41");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c)).as("export_jobs after re-applying V41").isTrue();
                assertThat(count(c, "select count(*) from flyway_schema_history where version = '41' and success"))
                        .isEqualTo(1);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static boolean tableExists(Connection c) throws SQLException {
        return count(c, "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'export_jobs'") == 1;
    }

    private static Map<String, String> columnNullability(Connection c) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select column_name, is_nullable from information_schema.columns where table_name = 'export_jobs'")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        }
        return result;
    }

    private static String dataType(Connection c, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select data_type from information_schema.columns where table_name = 'export_jobs' and column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Set<String> constraints(Connection c) throws SQLException {
        return strings(c, "select conname from pg_constraint where conrelid = 'export_jobs'::regclass");
    }

    private static Set<String> foreignKeyTargets(Connection c) throws SQLException {
        return strings(c, """
                select kcu.column_name || '->' || ccu.table_name || '.' || ccu.column_name
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu on kcu.constraint_name = tc.constraint_name
                join information_schema.constraint_column_usage ccu on ccu.constraint_name = tc.constraint_name
                where tc.table_name = 'export_jobs' and tc.constraint_type = 'FOREIGN KEY'
                """);
    }

    private static Set<String> indexes(Connection c) throws SQLException {
        return strings(c, "select indexname from pg_indexes where schemaname = 'public'");
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

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String insertTenant(Connection c) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Statement s = c.createStatement()) {
            s.execute("insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                    + id + "', 'V41 test tenant', 'v41-" + id + ".invalid', 'standard', 'active')");
        }
        return id;
    }

    private static String insertUser(Connection c, String tenantId) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Statement s = c.createStatement()) {
            s.execute("insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status) values ('"
                    + id + "', '" + tenantId + "', '" + UUID.randomUUID() + "', 'v41-" + id + "@test.invalid', 'v41-" + id
                    + "', 'V41', 'V41 Tester', 'active')");
        }
        return id;
    }

    private static String rollbackSql() throws Exception {
        try (InputStream in = V41ExportJobsMigrationTest.class.getResourceAsStream(ROLLBACK_SCRIPT)) {
            assertThat(in).as(ROLLBACK_SCRIPT + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, DB_USERNAME, DB_PASSWORD)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    @FunctionalInterface
    private interface ScratchTest {
        void run(String url) throws Exception;
    }

    private static void withScratchDatabase(ScratchTest test) throws Exception {
        String name = "dms_v41_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
