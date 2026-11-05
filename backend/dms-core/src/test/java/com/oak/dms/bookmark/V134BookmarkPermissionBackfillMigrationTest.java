package com.oak.dms.bookmark;

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

/**
 * V134 (document_bookmarks, and the backfill that grants bd.bookmark-unbookmark-documents.bookmark to
 * every role already holding docm.documents.view) on a scratch database - same harness as
 * {@code V87_...} in V47V48ImportExportMigrationTest and the other *MigrationTest classes.
 * <p>
 * This is the only place the backfill itself can be exercised: by the time any ordinary
 * {@code @SpringBootTest} IT (such as {@link BookmarkIT}) creates a tenant and a role, every migration -
 * including this one - has already run, so there is nothing left at that point for the backfill to
 * have acted on.
 */
class V134BookmarkPermissionBackfillMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    @Test
    void up_createsTheTable_andGrantsBookmarkOnlyToRolesThatAlreadyHoldDocumentView() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "133");
            String tenant = UUID.randomUUID().toString();
            String viewerRole = UUID.randomUUID().toString();
            String unrelatedRole = UUID.randomUUID().toString();
            try (Connection c = connect(url)) {
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                        + tenant + "', 'V134', 'v134-" + tenant + "', 'standard', 'active')");
                exec(c, "insert into roles (role_id, tenant_id, role_name, description) values ('" + viewerRole + "', '"
                        + tenant + "', 'Viewer', 'holds document view already')");
                exec(c, "insert into roles (role_id, tenant_id, role_name, description) values ('" + unrelatedRole
                        + "', '" + tenant + "', 'Unrelated', 'holds an unrelated permission')");
                exec(c, "insert into role_permissions (tenant_id, role_id, permission_id) select '" + tenant + "', '"
                        + viewerRole + "', permission_id from permissions where tenant_id is null "
                        + "and permission_code = 'docm.documents.view'");
                exec(c, "insert into role_permissions (tenant_id, role_id, permission_id) select '" + tenant + "', '"
                        + unrelatedRole + "', permission_id from permissions where tenant_id is null "
                        + "and permission_code = 'pid.pin-unpin-documents.pin'");
            }
            migrate(url, "134");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "document_bookmarks")).isTrue();

                // The role that already viewed documents can now bookmark them too, with nothing lost.
                assertThat(strings(c, "select p.permission_code from role_permissions rp join permissions p "
                        + "using (permission_id) where rp.role_id = '" + viewerRole + "'")).containsExactlyInAnyOrder(
                        "docm.documents.view", "bd.bookmark-unbookmark-documents.bookmark");

                // A role that never held document view is untouched.
                assertThat(strings(c, "select p.permission_code from role_permissions rp join permissions p "
                        + "using (permission_id) where rp.role_id = '" + unrelatedRole + "'"))
                        .containsExactly("pid.pin-unpin-documents.pin");

                assertThat(count(c, "select count(*) from flyway_schema_history where version = '134' and success"))
                        .isEqualTo(1);
            }
        });
    }

    @Test
    void up_isIdempotentForARoleThatAlreadyHoldsBothPermissions() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "133");
            String tenant = UUID.randomUUID().toString();
            String role = UUID.randomUUID().toString();
            try (Connection c = connect(url)) {
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                        + tenant + "', 'V134b', 'v134b-" + tenant + "', 'standard', 'active')");
                exec(c, "insert into roles (role_id, tenant_id, role_name) values ('" + role + "', '" + tenant
                        + "', 'Early adopter')");
                // Already holds both, e.g. an admin granted the new permission by hand before this ran.
                exec(c, "insert into role_permissions (tenant_id, role_id, permission_id) select '" + tenant + "', '"
                        + role + "', permission_id from permissions where tenant_id is null and permission_code in "
                        + "('docm.documents.view', 'bd.bookmark-unbookmark-documents.bookmark')");
            }
            migrate(url, "134");
            try (Connection c = connect(url)) {
                // Not duplicated: role_permissions has no unique constraint standing in the way here, so this is
                // the NOT EXISTS guard's own job to get right.
                assertThat(count(c, "select count(*) from role_permissions where role_id = '" + role
                        + "' and permission_id = (select permission_id from permissions where tenant_id is null "
                        + "and permission_code = 'bd.bookmark-unbookmark-documents.bookmark')")).isEqualTo(1);
            }
        });
    }

    // ---------------------------------------------------------------- harness

    private static boolean tableExists(Connection c, String table) throws SQLException {
        return count(c, "select count(*) from information_schema.tables where table_schema = 'public' and table_name = '"
                + table + "'") == 1;
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
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
        String name = "dms_v134_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
