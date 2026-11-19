package com.oak.dms.search;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V140-V143 (saved search presets, the preset-permission grants, the reactivation trigger and the admin run state) up and
 * down on a throwaway database, same harness as {@link V138ToV139SearchMigrationTest}.
 */
class V140ToV143SearchPhase2MigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String[] ROLLBACKS = {
            "/db/rollback/V143__search_admin_run_state.rollback.sql",
            "/db/rollback/V142__search_tenant_reactivation.rollback.sql",
            "/db/rollback/V141__grant_preset_permissions_to_search_users.rollback.sql",
            "/db/rollback/V140__create_saved_searches.rollback.sql"};
    private static final String SHARE = "dsr.manage-saved-search-presets.share";

    @Test
    void up_createsThePresetTables_andTheConstraintsBite() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "143");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "saved_searches")).isTrue();
                assertThat(tableExists(c, "saved_search_settings")).isTrue();
                String tenant = tenant(c);
                String user = user(c, tenant);
                String other = user(c, tenant);

                exec(c, "insert into saved_searches (tenant_id, user_id, name, criteria) values ('" + tenant + "', '" + user
                        + "', 'Quarterly', '{\"q\":\"x\"}')");
                // one name per person, whatever the case or surrounding spaces
                assertThatThrownBy(() -> exec(c, "insert into saved_searches (tenant_id, user_id, name, criteria) values ('"
                        + tenant + "', '" + user + "', '  QUARTERLY ', '{\"q\":\"y\"}')")).hasMessageContaining("ux_saved_searches_user_name");
                // another person may use it
                exec(c, "insert into saved_searches (tenant_id, user_id, name, criteria) values ('" + tenant + "', '" + other
                        + "', 'quarterly', '{\"q\":\"y\"}')");
                assertThatThrownBy(() -> exec(c, "insert into saved_searches (tenant_id, user_id, name, criteria) values ('"
                        + tenant + "', '" + user + "', '   ', '{\"q\":\"y\"}')")).hasMessageContaining("ck_saved_searches_name");
                assertThatThrownBy(() -> exec(c, "insert into saved_searches (tenant_id, user_id, name, criteria) values ('"
                        + tenant + "', '" + user + "', 'Array', '[1]')")).hasMessageContaining("ck_saved_searches_criteria");
                assertThat(text(c, "select schema_version::text from saved_searches where user_id = '" + user + "'")).isEqualTo("1");

                assertThatThrownBy(() -> exec(c, "insert into saved_search_settings (tenant_id, max_per_user) values ('" + tenant + "', 0)"))
                        .hasMessageContaining("saved_search_settings");
                assertThatThrownBy(() -> exec(c, "insert into saved_search_settings (tenant_id, max_per_user) values ('" + tenant + "', 501)"))
                        .hasMessageContaining("saved_search_settings");
                exec(c, "insert into saved_search_settings (tenant_id, max_per_user) values ('" + tenant + "', 25)");

                for (String column : new String[]{"last_reindex_requested_at", "last_reconcile_requested_at", "reconcile_running_until"}) {
                    assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'search_tenant_state'"
                            + " and column_name = '" + column + "'")).as(column).isEqualTo(1);
                }
            }
        });
    }

    @Test
    void v141_grantsCreateUpdateDeleteToSearchRoles_neverShare_andIsIdempotent() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "139");
            String searcher;
            String plain;
            String tenant;
            try (Connection c = connect(url)) {
                tenant = tenant(c);
                searcher = role(c, tenant, "Searcher", "dsr.search-documents", "docm.documents.view");
                plain = role(c, tenant, "Viewer", "docm.documents.view");
            }
            migrate(url, "141");
            try (Connection c = connect(url)) {
                for (String code : new String[]{"create", "update", "delete"}) {
                    assertThat(holds(c, searcher, "dsr.manage-saved-search-presets." + code)).as(code).isTrue();
                    assertThat(holds(c, plain, "dsr.manage-saved-search-presets." + code)).as("no search, no " + code).isFalse();
                }
                assertThat(holds(c, searcher, SHARE)).as("share stays ungranted").isFalse();
                assertThat(count(c, "select count(*) from role_permissions rp join permissions p on p.permission_id = rp.permission_id"
                        + " where p.permission_code = '" + SHARE + "'")).isZero();
                assertThat(count(c, "select count(*) from role_permissions where role_id = '" + searcher + "'")).isEqualTo(2 + 3);

                // re-running the grant (the rollback is a no-op on the data) adds nothing
                try (Statement s = c.createStatement()) {
                    s.execute(read("/db/rollback/V141__grant_preset_permissions_to_search_users.rollback.sql"));
                    s.execute("delete from flyway_schema_history where version = '141'");
                }
            }
            migrate(url, "141");
            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from role_permissions where role_id = '" + searcher + "'")).isEqualTo(5);
            }
        });
    }

    @Test
    void v142_reactivatingATenantResetsItsSearchStateSoItIsIndexedAgain() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "143");
            try (Connection c = connect(url)) {
                String tenant = tenant(c);
                exec(c, "insert into search_tenant_state (tenant_id, index_ready, backfill_state, backfill_cursor, last_backfill_at)"
                        + " values ('" + tenant + "', true, 'done', '" + uuid() + "', now())");

                exec(c, "update tenants set is_deleted = true, deleted_at = now() where tenant_id = '" + tenant + "'");
                assertThat(count(c, "select count(*) from search_index_jobs where tenant_id = '" + tenant + "' and kind = 'tenant_purge'")).isEqualTo(1);
                // removal alone does not touch the state; reactivation resets it
                assertThat(text(c, "select index_ready::text from search_tenant_state where tenant_id = '" + tenant + "'")).isEqualTo("true");

                exec(c, "update tenants set is_deleted = false, deleted_at = null where tenant_id = '" + tenant + "'");
                assertThat(text(c, "select index_ready::text from search_tenant_state where tenant_id = '" + tenant + "'")).isEqualTo("false");
                assertThat(text(c, "select backfill_state from search_tenant_state where tenant_id = '" + tenant + "'")).isEqualTo("none");
                assertThat(text(c, "select coalesce(backfill_cursor::text, 'null') from search_tenant_state where tenant_id = '" + tenant + "'")).isEqualTo("null");
                assertThat(text(c, "select coalesce(last_backfill_at::text, 'null') from search_tenant_state where tenant_id = '" + tenant + "'")).isEqualTo("null");

                // an unrelated tenant update does nothing, and a tenant with no state row does not fail
                String bare = tenant(c);
                exec(c, "update tenants set is_deleted = true where tenant_id = '" + bare + "'");
                exec(c, "update tenants set is_deleted = false where tenant_id = '" + bare + "'");
                exec(c, "update tenants set tenant_name = 'renamed' where tenant_id = '" + tenant + "'");
                assertThat(text(c, "select backfill_state from search_tenant_state where tenant_id = '" + tenant + "'")).isEqualTo("none");
            }
        });
    }

    @Test
    void down_removesEverythingThePhaseAddedAndUpAgainWorks() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "143");
            String tenant;
            try (Connection c = connect(url)) {
                tenant = tenant(c);
                exec(c, "insert into search_tenant_state (tenant_id, last_reindex_requested_at) values ('" + tenant + "', now())");
            }
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                for (String script : ROLLBACKS) {
                    s.execute(read(script));
                }
                s.execute("delete from flyway_schema_history where version in ('140', '141', '142', '143')");
                assertThat(tableExists(c, "saved_searches")).isFalse();
                assertThat(tableExists(c, "saved_search_settings")).isFalse();
                assertThat(count(c, "select count(*) from pg_trigger where tgname = 'trg_search_tenants_reactivated'")).isZero();
                assertThat(count(c, "select count(*) from pg_proc where proname = 'search_trg_tenants_reactivated'")).isZero();
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'search_tenant_state'"
                        + " and column_name in ('last_reindex_requested_at','last_reconcile_requested_at','reconcile_running_until')")).isZero();
                // Phase 1 is untouched
                assertThat(tableExists(c, "search_index_jobs")).isTrue();
                assertThat(count(c, "select count(*) from search_tenant_state where tenant_id = '" + tenant + "'")).isEqualTo(1);
                assertThat(count(c, "select count(*) from pg_trigger where tgname like 'trg_search_%'")).isEqualTo(12);
            }
            migrate(url, "143");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "saved_searches")).isTrue();
                assertThat(count(c, "select count(*) from pg_trigger where tgname = 'trg_search_tenants_reactivated'")).isEqualTo(1);
                assertThat(count(c, "select count(*) from flyway_schema_history where version in ('140','141','142','143') and success")).isEqualTo(4);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static String tenant(Connection c) throws SQLException {
        String tenant = uuid();
        exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                + "', 'P2 tenant', 'p2-" + tenant + ".invalid', 'standard', 'active')");
        return tenant;
    }

    private static String user(Connection c, String tenant) throws SQLException {
        String user = uuid();
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + user + "', '" + tenant + "', '" + uuid() + "', 'p2-" + user + "@test.invalid', 'p2-"
                + user + "', 'P2', 'P2', 'active')");
        return user;
    }

    private static String role(Connection c, String tenant, String name, String... codes) throws SQLException {
        String role = uuid();
        exec(c, "insert into roles (role_id, tenant_id, role_name) values ('" + role + "', '" + tenant + "', '" + name + " " + role + "')");
        for (String code : codes) {
            exec(c, "insert into role_permissions (tenant_id, role_id, permission_id) select '" + tenant + "', '" + role
                    + "', permission_id from permissions where permission_code = '" + code + "' and tenant_id is null");
        }
        return role;
    }

    private static boolean holds(Connection c, String role, String code) throws SQLException {
        return count(c, "select count(*) from role_permissions rp join permissions p on p.permission_id = rp.permission_id"
                + " where rp.role_id = '" + role + "' and p.permission_code = '" + code + "'") > 0;
    }

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

    private static String text(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String read(String script) throws Exception {
        try (InputStream in = V140ToV143SearchPhase2MigrationTest.class.getResourceAsStream(script)) {
            assertThat(in).as(script + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, DB_USERNAME, DB_PASSWORD).locations("classpath:db/migration").target(target)
                .load().migrate();
    }

    private interface ScratchTest {
        void run(String url) throws Exception;
    }

    private static void withScratchDatabase(ScratchTest test) throws Exception {
        String name = "dms_v140_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
