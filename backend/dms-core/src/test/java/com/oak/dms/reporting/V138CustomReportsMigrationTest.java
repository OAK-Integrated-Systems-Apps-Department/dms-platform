package com.oak.dms.reporting;

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
 * V138 (tables), V139 (permissions + the run-grant backfill) and V140 (notification categories) of Custom Report
 * Generation, on a scratch database that already holds V137, plus their rollback files. Same harness as
 * {@code V134BookmarkPermissionBackfillMigrationTest}: by the time an ordinary @SpringBootTest runs, every migration has
 * already acted, so the backfill can only be observed here.
 */
class V138CustomReportsMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    private static final String[] TABLES = {"tenant_report_settings", "report_field_settings", "report_templates",
            "report_template_grants", "report_schedules", "report_schedule_recipients", "report_runs", "report_run_deliveries"};

    @Test
    void up_createsTheTables_seedsThePermissions_andBackfillsTheRunGrantOnlyForRolesThatCanViewDocuments() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "137");
            String tenant = tenant(url);
            String viewerRole = UUID.randomUUID().toString();
            String unrelatedRole = UUID.randomUUID().toString();
            try (Connection c = connect(url)) {
                exec(c, "insert into roles (role_id, tenant_id, role_name) values ('" + viewerRole + "', '" + tenant + "', 'Viewer')");
                exec(c, "insert into roles (role_id, tenant_id, role_name) values ('" + unrelatedRole + "', '" + tenant + "', 'Unrelated')");
                exec(c, grant(tenant, viewerRole, "docm.documents.view"));
                exec(c, grant(tenant, unrelatedRole, "pid.pin-unpin-documents.pin"));
            }
            migrate(url, "140");
            try (Connection c = connect(url)) {
                for (String table : TABLES) {
                    assertThat(count(c, "select count(*) from information_schema.tables where table_schema = 'public' and table_name = '"
                            + table + "'")).as(table).isEqualTo(1);
                }

                assertThat(strings(c, "select permission_code from permissions where tenant_id is null and permission_code like 'crg.%'"))
                        .contains("crg.generate-custom-reports.build", "crg.generate-custom-reports.run",
                                "crg.schedule-automated-reports.create-schedule", "crg.schedule-automated-reports.update-cancel-schedule",
                                "crg.manage-report-templates.create", "crg.manage-report-templates.update",
                                "crg.manage-report-templates.delete", "crg.manage-report-templates.publish",
                                "crg.configure-reports.manage", "crg.view-all-runs.view");
                assertThat(count(c, "select count(*) from permissions p join permission_actions a using (action_id) "
                        + "where p.permission_code in ('crg.configure-reports.manage', 'crg.view-all-runs.view') and a.action_code in "
                        + "('CONFIGURE_REPORTS', 'VIEW_ALL_RUNS')")).isEqualTo(2);
                assertThat(count(c, "select count(*) from permissions where permission_code like 'crg.%' and description is null"))
                        .as("every CRG permission is described").isZero();

                // the role that could see documents can now run a report; nothing else changed for it
                assertThat(strings(c, rolePerms(viewerRole))).containsExactlyInAnyOrder("docm.documents.view", "crg.generate-custom-reports.run");
                // a role that never held document view is untouched
                assertThat(strings(c, rolePerms(unrelatedRole))).containsExactly("pid.pin-unpin-documents.pin");

                assertThat(strings(c, "select category_code || ':' || tier from notification_categories where category_code like 'report.%'"))
                        .containsExactlyInAnyOrder("report.ready:standard", "report.failed:critical", "report.schedule-paused:critical");
                assertThat(count(c, "select count(*) from flyway_schema_history where version in ('138','139','140') and success")).isEqualTo(3);
            }
        });
    }

    @Test
    void up_backfillIsIdempotent_forARoleThatAlreadyHoldsTheRunGrant() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "137");
            String tenant = tenant(url);
            String role = UUID.randomUUID().toString();
            try (Connection c = connect(url)) {
                exec(c, "insert into roles (role_id, tenant_id, role_name) values ('" + role + "', '" + tenant + "', 'Early adopter')");
                exec(c, grant(tenant, role, "docm.documents.view"));
                exec(c, grant(tenant, role, "crg.generate-custom-reports.run"));
            }
            migrate(url, "140");
            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from role_permissions where role_id = '" + role + "' and permission_id = "
                        + "(select permission_id from permissions where tenant_id is null and permission_code = 'crg.generate-custom-reports.run')"))
                        .isEqualTo(1);
            }
        });
    }

    @Test
    void aScheduledSlotFiresOncePerDeliveryTarget_soTheOwnersRunAndTheExternalRunDoNotCollide() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "140");
            String tenant = tenant(url);
            String owner = user(url, tenant, "owner");
            String alice = user(url, tenant, "alice");
            String bob = user(url, tenant, "bob");
            try (Connection c = connect(url)) {
                String template = uuid();
                exec(c, "insert into report_templates (template_id, tenant_id, owner_user_id, scope, name, definition) values ('"
                        + template + "', '" + tenant + "', '" + owner + "', 'private', 'Weekly', '{}')");
                String schedule = uuid();
                exec(c, "insert into report_schedules (schedule_id, tenant_id, template_id, owner_user_id, name, frequency, time_of_day, "
                        + "time_zone, format, delivery_channels) values ('" + schedule + "', '" + tenant + "', '" + template + "', '" + owner
                        + "', 'Weekly', 'daily', '08:00', 'UTC', 'csv', ARRAY['in_app'])");

                String slot = "2026-01-05 08:00:00+00";
                exec(c, run(tenant, schedule, owner, null, "owner", slot));
                exec(c, run(tenant, schedule, owner, null, "external", slot));      // same slot, other delivery target: allowed
                exec(c, run(tenant, schedule, owner, alice, "recipient", slot));
                exec(c, run(tenant, schedule, owner, bob, "recipient", slot));
                assertThat(count(c, "select count(*) from report_runs where schedule_id = '" + schedule + "'")).isEqualTo(4);

                // two scheduler instances firing the same slot for the same target: the second insert is refused
                assertThatThrownBy(() -> exec(c, run(tenant, schedule, owner, null, "owner", slot)))
                        .hasMessageContaining("ux_report_runs_schedule_slot");
                assertThatThrownBy(() -> exec(c, run(tenant, schedule, owner, alice, "recipient", slot)))
                        .hasMessageContaining("ux_report_runs_schedule_slot");

                // the next slot is a different run
                exec(c, run(tenant, schedule, owner, null, "owner", "2026-01-06 08:00:00+00"));

                // manual runs (no schedule) are never constrained by the slot key
                exec(c, "insert into report_runs (tenant_id, requested_by, trigger, format, definition_snapshot) values ('" + tenant + "', '"
                        + owner + "', 'manual', 'xlsx', '{}')");
                exec(c, "insert into report_runs (tenant_id, requested_by, trigger, format, definition_snapshot) values ('" + tenant + "', '"
                        + owner + "', 'manual', 'xlsx', '{}')");
            }
        });
    }

    @Test
    void aRecipientScopedRunMustNameTheRecipient_andNoOtherRunMay() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "140");
            String tenant = tenant(url);
            String owner = user(url, tenant, "owner");
            try (Connection c = connect(url)) {
                String base = "insert into report_runs (tenant_id, requested_by, for_user_id, delivery_scope, trigger, format, definition_snapshot) values ('"
                        + tenant + "', '" + owner + "', ";
                assertThatThrownBy(() -> exec(c, base + "null, 'recipient', 'manual', 'csv', '{}')")).hasMessageContaining("ck_report_runs_recipient");
                assertThatThrownBy(() -> exec(c, base + "'" + owner + "', 'owner', 'manual', 'csv', '{}')")).hasMessageContaining("ck_report_runs_recipient");
                assertThatThrownBy(() -> exec(c, base + "null, 'owner', 'manual', 'docx', '{}')")).hasMessageContaining("ck_report_runs_format");
            }
        });
    }

    @Test
    void tenantSettings_noRowIsFine_defaultsMatchTheBrief_andTheChecksRefuseNonsense() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "140");
            String tenant = tenant(url);
            try (Connection c = connect(url)) {
                exec(c, "insert into tenant_report_settings (tenant_id) values ('" + tenant + "')");
                assertThat(strings(c, "select allowed_formats::text || '|' || default_format || '|' || csv_delimiter || '|' || pdf_page_size "
                        + "|| '|' || pdf_orientation || '|' || over_limit_behaviour || '|' || min_schedule_interval || '|' || allow_external_recipients "
                        + "|| '|' || allow_attachments || '|' || allowed_delivery_channels::text from tenant_report_settings where tenant_id = '" + tenant + "'"))
                        .containsExactly("{pdf,xlsx,csv}|xlsx|,|A4|landscape|fail|daily|false|false|{in_app,email}");
                exec(c, "delete from tenant_report_settings where tenant_id = '" + tenant + "'");

                String ins = "insert into tenant_report_settings (tenant_id, ";
                assertThatThrownBy(() -> exec(c, ins + "csv_delimiter) values ('" + tenant + "', '|')")).hasMessageContaining("ck_trs_csv_delimiter");
                assertThatThrownBy(() -> exec(c, ins + "allowed_formats) values ('" + tenant + "', ARRAY[]::text[])")).hasMessageContaining("ck_trs_allowed_formats");
                assertThatThrownBy(() -> exec(c, ins + "allowed_formats, default_format) values ('" + tenant + "', ARRAY['csv'], 'pdf')"))
                        .hasMessageContaining("ck_trs_default_in_allowed");
                assertThatThrownBy(() -> exec(c, ins + "max_rows) values ('" + tenant + "', 0)")).hasMessageContaining("ck_trs_positive");
                assertThatThrownBy(() -> exec(c, ins + "min_schedule_interval) values ('" + tenant + "', 'weekly')")).hasMessageContaining("ck_trs_min_interval");
                exec(c, ins + "allowed_formats, default_format, csv_delimiter) values ('" + tenant + "', ARRAY['csv'], 'csv', E'\\t')");
            }
        });
    }

    @Test
    void templateNamesAreUniquePerOwnerOrPerTenantForOfficial_caseInsensitive_ignoringSoftDeleted() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "140");
            String tenant = tenant(url);
            String alice = user(url, tenant, "alice");
            String bob = user(url, tenant, "bob");
            try (Connection c = connect(url)) {
                exec(c, template(tenant, alice, "private", "Monthly Contracts"));
                exec(c, template(tenant, bob, "private", "Monthly Contracts"));               // another owner: fine
                assertThatThrownBy(() -> exec(c, template(tenant, alice, "shared", "monthly contracts")))
                        .hasMessageContaining("ux_report_templates_owner_name");

                exec(c, template(tenant, null, "official", "Quarterly Review"));
                assertThatThrownBy(() -> exec(c, template(tenant, null, "official", "QUARTERLY REVIEW")))
                        .hasMessageContaining("ux_report_templates_official_name");

                exec(c, "update report_templates set deleted_at = now() where owner_user_id = '" + alice + "'");
                exec(c, template(tenant, alice, "private", "Monthly Contracts"));             // soft-deleted name is free again

                // official templates are owned by the tenant; private/shared by a person
                assertThatThrownBy(() -> exec(c, template(tenant, alice, "official", "Owned official"))).hasMessageContaining("ck_report_templates_owner");
                assertThatThrownBy(() -> exec(c, template(tenant, null, "private", "Orphan"))).hasMessageContaining("ck_report_templates_owner");
            }
        });
    }

    @Test
    void rollbackFilesUndoTheMigrations_onADatabaseThatAlreadyHasV137() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "140");
            try (Connection c = connect(url)) {
                // a notification of a report category must not block the rollback
                runResource(c, "db/rollback/V140__custom_reports_notification_categories.rollback.sql");
                runResource(c, "db/rollback/V139__custom_reports_permissions.rollback.sql");
                runResource(c, "db/rollback/V138__create_custom_reports.rollback.sql");

                for (String table : TABLES) {
                    assertThat(count(c, "select count(*) from information_schema.tables where table_schema = 'public' and table_name = '"
                            + table + "'")).as(table).isZero();
                }
                assertThat(count(c, "select count(*) from permissions where permission_code in ('crg.configure-reports.manage', "
                        + "'crg.manage-report-templates.publish', 'crg.view-all-runs.view')")).isZero();
                assertThat(count(c, "select count(*) from notification_categories where category_code like 'report.%'")).isZero();
                // the seven pre-existing CRG permissions (V10) are not ours to remove
                assertThat(count(c, "select count(*) from permissions where permission_code like 'crg.%' and tenant_id is null")).isEqualTo(7);
            }
        });
    }

    @Test
    void v141AddsTheRunProgressAndStorageColumns_andItsRollbackRemovesOnlyThose() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "141");
            try (Connection c = connect(url)) {
                assertThat(strings(c, "select column_name from information_schema.columns where table_name = 'report_runs' "
                        + "and column_name in ('progress_rows', 'storage_location_id')")).containsExactlyInAnyOrder("progress_rows", "storage_location_id");
                assertThat(strings(c, "select column_default from information_schema.columns where table_name = 'report_runs' "
                        + "and column_name = 'progress_rows'")).containsExactly("0");

                runResource(c, "db/rollback/V141__report_runs_progress_and_storage.rollback.sql");

                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'report_runs' "
                        + "and column_name in ('progress_rows', 'storage_location_id')")).isZero();
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'report_runs' "
                        + "and column_name in ('status', 'file_object_key', 'row_count')")).isEqualTo(3);
            }
        });
    }

    // ---------------------------------------------------------------- fixtures

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String tenant(String url) throws SQLException {
        String tenant = uuid();
        try (Connection c = connect(url)) {
            exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                    + "', 'CRG', 'crg-" + tenant + "', 'standard', 'active')");
        }
        return tenant;
    }

    private static String user(String url, String tenant, String name) throws SQLException {
        String id = uuid();
        try (Connection c = connect(url)) {
            exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username) values ('" + id
                    + "', '" + tenant + "', '" + uuid() + "', '" + name + "@example.com', '" + name + "', '" + name + "', 'Tester', '"
                    + name + "-" + id.substring(0, 8) + "')");
        }
        return id;
    }

    private static String grant(String tenant, String role, String code) {
        return "insert into role_permissions (tenant_id, role_id, permission_id) select '" + tenant + "', '" + role
                + "', permission_id from permissions where tenant_id is null and permission_code = '" + code + "'";
    }

    private static String rolePerms(String role) {
        return "select p.permission_code from role_permissions rp join permissions p using (permission_id) where rp.role_id = '" + role + "'";
    }

    private static String run(String tenant, String schedule, String owner, String forUser, String scope, String slot) {
        return "insert into report_runs (tenant_id, schedule_id, requested_by, for_user_id, delivery_scope, trigger, format, "
                + "definition_snapshot, scheduled_for) values ('" + tenant + "', '" + schedule + "', '" + owner + "', "
                + (forUser == null ? "null" : "'" + forUser + "'") + ", '" + scope + "', 'scheduled', 'csv', '{}', '" + slot + "')";
    }

    private static String template(String tenant, String owner, String scope, String name) {
        return "insert into report_templates (tenant_id, owner_user_id, scope, name, definition) values ('" + tenant + "', "
                + (owner == null ? "null" : "'" + owner + "'") + ", '" + scope + "', '" + name + "', '{}')";
    }

    // ---------------------------------------------------------------- harness

    private static void runResource(Connection c, String path) throws Exception {
        try (InputStream in = V138CustomReportsMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            exec(c, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
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
        String name = "dms_v138_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
