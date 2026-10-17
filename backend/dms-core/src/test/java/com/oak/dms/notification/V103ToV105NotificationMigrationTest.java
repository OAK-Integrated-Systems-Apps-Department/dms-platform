package com.oak.dms.notification;

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
 * V103–V105 (3.2 Notification: core tables + seeds, workflow reminder/escalation
 * columns, permissions) up and down on a throwaway database - the same harness
 * and seed approach as {@link com.oak.dms.workflow.V95ToV98WorkflowPhase2MigrationTest}.
 * Proves the new CHECKs bite, the append-only trigger rejects UPDATE/DELETE
 * even for the owning role the test connects as (plan Deviation #1), the
 * dedupe unique holds, the category and permission seeds landed, and that the
 * three rollback scripts reverse cleanly and V103–V105 re-apply.
 */
class V103ToV105NotificationMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String[] ROLLBACKS = {
            "/db/rollback/V105__seed_notification_permissions.rollback.sql",
            "/db/rollback/V104__add_workflow_notification_config_columns.rollback.sql",
            "/db/rollback/V103__create_notification_core.rollback.sql"};
    private static final String[] TABLES = {"notification_categories", "tenant_notification_settings",
            "user_notification_preferences", "notifications", "notification_deliveries",
            "notification_delivery_attempts"};
    private static final String[] PERMISSIONS = {"notif.notification-settings.manage",
            "notif.delivery-log.view", "notif.delivery-log.retry"};

    @Test
    void up_addsTablesColumnsSeedsAndConstraints_thatBite() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "102");
            Ids ids;
            try (Connection c = connect(url)) {
                ids = seed(c);
            }
            migrate(url, "105");
            try (Connection c = connect(url)) {
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table).isTrue();
                }
                // Category seed: 18 rows, 8 critical.
                assertThat(count(c, "select count(*) from notification_categories")).isEqualTo(18);
                assertThat(count(c, "select count(*) from notification_categories where tier = 'critical'")).isEqualTo(8);
                for (String code : PERMISSIONS) {
                    assertThat(count(c, "select count(*) from permissions where permission_code = '" + code + "'"))
                            .as(code).isEqualTo(1);
                }
                assertThat(count(c, "select count(*) from permission_modules where module_code = 'NOTIF'")).isEqualTo(1);

                // Workflow config columns exist with their defaults.
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_templates'"
                        + " and column_name in ('default_reminder_before_hours','default_inactivity_reminder_hours',"
                        + "'default_escalate_after_reminder_hours','default_escalation_target','default_escalation_user_id')"))
                        .isEqualTo(5);
                assertThat(columnDefault(c, "workflow_templates", "default_escalation_target")).contains("none");
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_stages'"
                        + " and column_name in ('inactivity_reminder_hours','escalate_after_reminder_hours')")).isEqualTo(2);
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_tasks'"
                        + " and column_name in ('last_activity_at','inactivity_reminder_sent_at')")).isEqualTo(2);
                assertThat(indexes(c)).contains("ix_notifications_unread", "ix_notification_deliveries_claim",
                        "ix_workflow_tasks_inactivity", "uq_notifications_recipient_dedupe");

                // Category CHECKs bite.
                assertThatThrownBy(() -> exec(c, "insert into notification_categories (category_code, module, tier,"
                        + " default_email_enabled, display_name) values ('x.y.z', 'm', 'bogus', true, 'X')"))
                        .hasMessageContaining("ck_notification_categories_tier");
                assertThatThrownBy(() -> exec(c, "insert into notification_categories (category_code, module, tier,"
                        + " default_email_enabled, display_name) values ('x.y.z', 'm', 'critical', false, 'X')"))
                        .hasMessageContaining("ck_notification_categories_critical_email");

                // A notification + delivery + attempt.
                String notification = uuid();
                exec(c, "insert into notifications (notification_id, tenant_id, recipient_user_id, category_code, tier,"
                        + " title, source_module, dedupe_key) values ('" + notification + "', '" + ids.tenant() + "', '"
                        + ids.user() + "', 'comment.mention', 'standard', 'You were mentioned', 'documents', 'k1')");
                // Dedupe unique: same (tenant, recipient, dedupe_key) is rejected.
                assertThatThrownBy(() -> exec(c, "insert into notifications (tenant_id, recipient_user_id, category_code,"
                        + " tier, title, source_module, dedupe_key) values ('" + ids.tenant() + "', '" + ids.user()
                        + "', 'comment.mention', 'standard', 'dup', 'documents', 'k1')"))
                        .hasMessageContaining("uq_notifications_recipient_dedupe");

                String delivery = uuid();
                // skipped without a reason is rejected.
                assertThatThrownBy(() -> exec(c, "insert into notification_deliveries (tenant_id, notification_id, channel,"
                        + " status) values ('" + ids.tenant() + "', '" + notification + "', 'email', 'skipped')"))
                        .hasMessageContaining("ck_notification_deliveries_skip_reason");
                // sent without sent_at is rejected.
                assertThatThrownBy(() -> exec(c, "insert into notification_deliveries (tenant_id, notification_id, channel,"
                        + " status) values ('" + ids.tenant() + "', '" + notification + "', 'email', 'sent')"))
                        .hasMessageContaining("ck_notification_deliveries_sent");
                exec(c, "insert into notification_deliveries (delivery_id, tenant_id, notification_id, channel, status,"
                        + " destination) values ('" + delivery + "', '" + ids.tenant() + "', '" + notification
                        + "', 'email', 'pending', 'a@test.invalid')");

                String attempt = uuid();
                exec(c, "insert into notification_delivery_attempts (attempt_id, tenant_id, delivery_id, notification_id,"
                        + " recipient_user_id, attempt_number, channel, outcome) values ('" + attempt + "', '" + ids.tenant()
                        + "', '" + delivery + "', '" + notification + "', '" + ids.user() + "', 1, 'email', 'failed')");
                // Append-only: UPDATE and DELETE are both blocked by the trigger, even as the owner.
                assertThatThrownBy(() -> exec(c, "update notification_delivery_attempts set outcome = 'sent' where"
                        + " attempt_id = '" + attempt + "'")).hasMessageContaining("append-only");
                assertThatThrownBy(() -> exec(c, "delete from notification_delivery_attempts where attempt_id = '"
                        + attempt + "'")).hasMessageContaining("append-only");

                // Workflow template escalation CHECK: named_user needs a user.
                assertThatThrownBy(() -> exec(c, "update workflow_templates set default_escalation_target = 'named_user'"
                        + " where template_id = '" + ids.template() + "'"))
                        .hasMessageContaining("ck_workflow_templates_default_escalation_user");
                exec(c, "update workflow_templates set default_escalation_target = 'named_user',"
                        + " default_escalation_user_id = '" + ids.user() + "' where template_id = '" + ids.template() + "'");
                // Stage inactivity hours must be positive.
                assertThatThrownBy(() -> exec(c, "update workflow_stages set inactivity_reminder_hours = 0 where"
                        + " stage_id = '" + ids.stage() + "'"))
                        .hasMessageContaining("ck_workflow_stages_inactivity_reminder_hours");
            }
        });
    }

    @Test
    void down_removesEverythingCleanly_andUpCanBeReapplied() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "105");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                for (String script : ROLLBACKS) {
                    s.execute(read(script));
                }
                s.execute("delete from flyway_schema_history where version in ('103', '104', '105')");
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table + " after rollback").isFalse();
                }
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_templates'"
                        + " and column_name = 'default_escalation_target'")).isZero();
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_tasks'"
                        + " and column_name in ('last_activity_at','inactivity_reminder_sent_at')")).isZero();
                assertThat(count(c, "select count(*) from permission_modules where module_code = 'NOTIF'")).isZero();
                for (String code : PERMISSIONS) {
                    assertThat(count(c, "select count(*) from permissions where permission_code = '" + code + "'"))
                            .as(code).isZero();
                }
            }
            migrate(url, "105");
            try (Connection c = connect(url)) {
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table + " after re-applying").isTrue();
                }
                assertThat(count(c, "select count(*) from flyway_schema_history where version in ('103','104','105')"
                        + " and success")).isEqualTo(3);
            }
        });
    }

    // ---------------------------------------------------------------- seed

    private record Ids(String tenant, String user, String type, String template, String stage) {
    }

    /** The smallest graph needed: a tenant, a user, a document type, a template and a stage. */
    private static Ids seed(Connection c) throws SQLException {
        String tenant = uuid();
        String user = uuid();
        String type = uuid();
        String template = uuid();
        String stage = uuid();
        exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                + "', 'V103 tenant', 'v103-" + tenant + ".invalid', 'standard', 'active')");
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + user + "', '" + tenant + "', '" + uuid() + "', 'v103-" + user + "@test.invalid', 'v103-"
                + user + "', 'V103', 'V103', 'active')");
        exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('" + type + "', '"
                + tenant + "', 'CONTRACT', 'Contract')");
        exec(c, "insert into workflow_templates (template_id, tenant_id, document_type_id, name, code, status) values ('"
                + template + "', '" + tenant + "', '" + type + "', 'A', 'A', 'active')");
        exec(c, "insert into workflow_stages (stage_id, tenant_id, template_id, stage_order, name, completion_rule,"
                + " rejection_rule) values ('" + stage + "', '" + tenant + "', '" + template
                + "', 1, 'S1', 'all', 'halt_immediately')");
        return new Ids(tenant, user, type, template, stage);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean tableExists(Connection c, String table) throws SQLException {
        return count(c, "select count(*) from information_schema.tables where table_schema = 'public' and table_name = '"
                + table + "'") == 1;
    }

    private static Set<String> indexes(Connection c) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select indexname from pg_indexes where schemaname = 'public'")) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    private static String columnDefault(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select column_default from information_schema.columns where table_name = '" + table
                        + "' and column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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
        try (InputStream in = V103ToV105NotificationMigrationTest.class.getResourceAsStream(script)) {
            assertThat(in).as(script + " on the classpath").isNotNull();
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
        String name = "dms_v82_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
