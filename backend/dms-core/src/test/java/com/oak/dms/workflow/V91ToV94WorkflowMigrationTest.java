package com.oak.dms.workflow;

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
 * V91-V94 (departments, workflow configuration, workflow runtime, department
 * permissions) up and down on a throwaway database - the V41ExportJobsMigrationTest
 * harness. Proves the partial unique indexes that carry the locked decisions
 * (one active template per type, one in-progress instance per document, one
 * active stage per instance, one decision per user per stage) actually bite,
 * and that the four rollback scripts reverse cleanly and V91-V94 re-apply.
 * Named *Test so CI runs it (only needs Postgres with CREATE DATABASE).
 */
class V91ToV94WorkflowMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String[] ROLLBACKS = {
            "/db/rollback/V94__seed_department_permissions.rollback.sql",
            "/db/rollback/V93__create_workflow_runtime.rollback.sql",
            "/db/rollback/V92__create_workflow_templates.rollback.sql",
            "/db/rollback/V91__create_departments.rollback.sql"};
    private static final String[] TABLES = {"departments", "workflow_templates", "workflow_stages",
            "workflow_stage_participants", "workflow_instances", "workflow_stage_instances", "workflow_tasks",
            "workflow_actions"};

    @Test
    void up_createsTablesConstraintsAndThePartialUniqueIndexes() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "90");
            try (Connection c = connect(url)) {
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table + " before V91").isFalse();
                }
            }
            migrate(url, "94");
            try (Connection c = connect(url)) {
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table).isTrue();
                }
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'users'"
                        + " and column_name = 'department_id' and is_nullable = 'YES'")).isEqualTo(1);
                assertThat(indexes(c)).contains(
                        "uq_departments_tenant_code",
                        "uq_workflow_templates_one_active_per_type",
                        "uq_workflow_templates_tenant_code_version",
                        "uq_workflow_templates_one_draft_per_code",
                        "uq_workflow_stage_participants_stage_assignee",
                        "uq_workflow_instances_one_in_progress",
                        "uq_workflow_stage_instances_one_active",
                        "uq_workflow_tasks_one_decision_per_user",
                        "ix_workflow_tasks_open_assignee");
                assertThat(indexDefinition(c, "uq_workflow_instances_one_in_progress"))
                        .contains("(tenant_id, document_id)").contains("WHERE").contains("in_progress");
                assertThat(indexDefinition(c, "uq_workflow_templates_one_active_per_type"))
                        .contains("(tenant_id, document_type_id)").contains("active");
                assertThat(constraints(c, "workflow_instances")).contains("fk_workflow_instances_current_stage",
                        "fk_workflow_instances_document_version", "ck_workflow_instances_outcome_reason");
                assertThat(count(c, "select count(*) from pg_constraint where conname = 'fk_workflow_instances_current_stage'"
                        + " and condeferrable and condeferred")).isEqualTo(1);
                assertThat(count(c, "select count(*) from pg_constraint where conname = 'uq_workflow_stages_template_order'"
                        + " and condeferrable")).isEqualTo(1);
                assertThat(count(c, "select count(*) from permissions where permission_code like 'otua.manage-departments.%'"))
                        .isEqualTo(4);

                Ids ids = seed(c);
                // Decision 7: a second active template for the same document type.
                assertThatThrownBy(() -> exec(c, "insert into workflow_templates (tenant_id, document_type_id, name, code, status)"
                        + " values ('" + ids.tenant + "', '" + ids.type + "', 'B', 'B', 'active')"))
                        .hasMessageContaining("uq_workflow_templates_one_active_per_type");
                // Decision 8: a second in-progress instance for the same document.
                assertThatThrownBy(() -> exec(c, insertInstance(ids)))
                        .hasMessageContaining("uq_workflow_instances_one_in_progress");
                // One active stage per instance.
                assertThatThrownBy(() -> exec(c, "insert into workflow_stage_instances (tenant_id, instance_id, stage_id,"
                        + " stage_order, status) values ('" + ids.tenant + "', '" + ids.instance + "', '" + ids.stage
                        + "', 2, 'active')"))
                        .hasMessageContaining("uq_workflow_stage_instances_one_active");
                // A rejection needs a reason.
                assertThatThrownBy(() -> exec(c, "update workflow_tasks set status = 'rejected', decision = 'reject',"
                        + " acted_by = '" + ids.user + "', acted_at = now(), decision_reason = '  ' where task_id = '"
                        + ids.task + "'"))
                        .hasMessageContaining("ck_workflow_tasks_reject_reason");
                // Phase 2 statuses are already allowed by the CHECK.
                exec(c, "update workflow_tasks set status = 'awaiting_info' where task_id = '" + ids.task + "'");
                exec(c, "update workflow_tasks set status = 'pending' where task_id = '" + ids.task + "'");
                // WA-FR-013: one decision per user per stage instance.
                exec(c, "update workflow_tasks set status = 'approved', decision = 'approve', acted_by = '" + ids.user
                        + "', acted_at = now() where task_id = '" + ids.task + "'");
                assertThatThrownBy(() -> exec(c, "update workflow_tasks set status = 'approved', decision = 'approve',"
                        + " acted_by = '" + ids.user + "', acted_at = now() where task_id = '" + ids.task2 + "'"))
                        .hasMessageContaining("uq_workflow_tasks_one_decision_per_user");
            }
        });
    }

    @Test
    void down_removesEverythingCleanly_andUpCanBeReapplied() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "94");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                for (String script : ROLLBACKS) {
                    s.execute(read(script));
                }
                s.execute("delete from flyway_schema_history where version in ('91', '92', '93', '94')");
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table + " after rollback").isFalse();
                }
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'users'"
                        + " and column_name = 'department_id'")).isZero();
                assertThat(count(c, "select count(*) from permissions where permission_code like 'otua.manage-departments.%'"))
                        .isZero();
            }
            migrate(url, "94");
            try (Connection c = connect(url)) {
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table + " after re-applying").isTrue();
                }
                assertThat(count(c, "select count(*) from flyway_schema_history where version in ('91','92','93','94')"
                        + " and success")).isEqualTo(4);
            }
        });
    }

    // ---------------------------------------------------------------- seed

    private record Ids(String tenant, String user, String type, String document, String version, String template,
                       String stage, String participant, String participant2, String instance, String stageInstance,
                       String task, String task2) {
    }

    /** The smallest graph that reaches every runtime table - satisfying the V39 scan-gate trigger. */
    private static Ids seed(Connection c) throws SQLException {
        String tenant = uuid();
        String user = uuid();
        String type = uuid();
        String document = uuid();
        String version = uuid();
        String location = uuid();
        String staging = uuid();
        String engine = uuid();
        String template = uuid();
        String stage = uuid();
        String participant = uuid();
        String participant2 = uuid();
        String instance = uuid();
        String stageInstance = uuid();
        String task = uuid();
        String task2 = uuid();
        exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                + "', 'V91 tenant', 'v91-" + tenant + ".invalid', 'standard', 'active')");
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + user + "', '" + tenant + "', '" + uuid() + "', 'v91-" + user + "@test.invalid', 'v91-"
                + user + "', 'V91', 'V91', 'active')");
        exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('" + type + "', '"
                + tenant + "', 'CONTRACT', 'Contract')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + location + "', '" + tenant + "', 'v43', 'FILE_SYSTEM', 'document', '/tmp/v43', 'active')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + staging + "', '" + tenant + "', 'v91-staging', 'FILE_SYSTEM', 'staging', '/tmp/v43s', 'active')");
        exec(c, "insert into scan_engines (scan_engine_id, tenant_id, name, provider, scan_mode, timeout_seconds,"
                + " max_file_size_bytes, status) values ('" + engine + "', '" + tenant + "', 'v43', 'CLAMAV',"
                + " 'pre_store_stream', 60, 1000, 'active')");
        // The scan-gate trigger and the documents <-> versions FKs are deferred to commit.
        c.setAutoCommit(false);
        exec(c, "insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id)"
                + " values ('" + document + "', '" + tenant + "', '" + type + "', 'Doc', '" + version + "', '" + user + "')");
        exec(c, "insert into document_versions (version_id, document_id, tenant_id, version_number, major_version,"
                + " minor_version, storage_location_id, storage_object_key, original_filename, mime_type, file_hash,"
                + " size_bytes, created_reason, scan_status) values ('" + version + "', '" + document + "', '" + tenant
                + "', 1, 1, 0, '" + location + "', 'k', 'a.pdf', 'application/pdf', 'h', 1, 'initial_upload', 'clean')");
        exec(c, "insert into scan_results (tenant_id, scan_engine_id, document_version_id, staging_location_id,"
                + " original_filename, file_hash, size_bytes, mime_type, status, engine_name, scan_mode, completed_at,"
                + " uploaded_by, ingestion_kind, created_reason, document_type_id, title) values ('" + tenant + "', '"
                + engine + "', '" + version + "', '" + staging + "', 'a.pdf', 'h', 1, 'application/pdf', 'clean', 'v43',"
                + " 'pre_store_stream', now(), '" + user + "', 'new_document', 'initial_upload', '" + type + "', 'Doc')");
        c.commit();
        c.setAutoCommit(true);
        exec(c, "insert into workflow_templates (template_id, tenant_id, document_type_id, name, code, status) values ('"
                + template + "', '" + tenant + "', '" + type + "', 'A', 'A', 'active')");
        exec(c, "insert into workflow_stages (stage_id, tenant_id, template_id, stage_order, name, completion_rule,"
                + " rejection_rule) values ('" + stage + "', '" + tenant + "', '" + template + "', 1, 'S1', 'all', 'halt_immediately')");
        exec(c, "insert into workflow_stage_participants (participant_id, tenant_id, stage_id, assignee_type, assignee_id)"
                + " values ('" + participant + "', '" + tenant + "', '" + stage + "', 'user', '" + user + "')");
        exec(c, "insert into workflow_stage_participants (participant_id, tenant_id, stage_id, assignee_type, assignee_id)"
                + " values ('" + participant2 + "', '" + tenant + "', '" + stage + "', 'role', '" + uuid() + "')");
        Ids ids = new Ids(tenant, user, type, document, version, template, stage, participant, participant2, instance,
                stageInstance, task, task2);
        exec(c, insertInstance(ids).replace("(tenant_id,", "(instance_id, tenant_id,").replace("values (",
                "values ('" + instance + "', "));
        exec(c, "insert into workflow_stage_instances (stage_instance_id, tenant_id, instance_id, stage_id, stage_order,"
                + " status, entry_count, required_count) values ('" + stageInstance + "', '" + tenant + "', '" + instance
                + "', '" + stage + "', 1, 'active', 2, 2)");
        for (String[] t : new String[][]{{task, participant, "user"}, {task2, participant2, "role"}}) {
            exec(c, "insert into workflow_tasks (task_id, tenant_id, stage_instance_id, participant_id, assignee_type,"
                    + " assignee_id) values ('" + t[0] + "', '" + tenant + "', '" + stageInstance + "', '" + t[1] + "', '"
                    + t[2] + "', '" + user + "')");
        }
        return ids;
    }

    private static String insertInstance(Ids ids) {
        return "insert into workflow_instances (tenant_id, document_id, document_version_id, template_id,"
                + " template_version_number, initiated_by) values ('" + ids.tenant + "', '" + ids.document + "', '"
                + ids.version + "', '" + ids.template + "', 1, '" + ids.user + "')";
    }

    // ---------------------------------------------------------------- helpers

    private static boolean tableExists(Connection c, String table) throws SQLException {
        return count(c, "select count(*) from information_schema.tables where table_schema = 'public' and table_name = '"
                + table + "'") == 1;
    }

    private static Set<String> indexes(Connection c) throws SQLException {
        return strings(c, "select indexname from pg_indexes where schemaname = 'public'");
    }

    private static String indexDefinition(Connection c, String index) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select indexdef from pg_indexes where indexname = '" + index + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Set<String> constraints(Connection c, String table) throws SQLException {
        return strings(c, "select conname from pg_constraint where conrelid = '" + table + "'::regclass");
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

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String read(String script) throws Exception {
        try (InputStream in = V91ToV94WorkflowMigrationTest.class.getResourceAsStream(script)) {
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
        String name = "dms_v43_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
