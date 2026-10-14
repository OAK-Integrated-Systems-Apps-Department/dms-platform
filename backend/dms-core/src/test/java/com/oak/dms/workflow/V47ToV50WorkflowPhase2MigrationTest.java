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
 * V47-V50 (Phase 2: task/stage columns, information requests and supporting
 * documents, comments and mentions, permissions) up and down on a throwaway
 * database - the V43ToV46WorkflowMigrationTest harness and seed graph.
 * Proves the new CHECKs bite (awaiting_info needs a holder, the SLA pause
 * only while awaiting_info, a decline needs a reason, one supporting row
 * per version), that the re-created index predicates include awaiting_info,
 * and that the four rollback scripts reverse cleanly and V47-V50 re-apply.
 */
class V47ToV50WorkflowPhase2MigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String[] ROLLBACKS = {
            "/db/rollback/V50__seed_workflow_phase2_permissions.rollback.sql",
            "/db/rollback/V49__create_document_comments.rollback.sql",
            "/db/rollback/V48__create_workflow_info_requests_and_supporting_documents.rollback.sql",
            "/db/rollback/V47__workflow_phase2_task_and_stage_columns.rollback.sql"};
    private static final String[] TABLES = {"workflow_info_requests", "workflow_supporting_documents",
            "document_comments", "comment_mentions"};
    private static final String[] PERMISSIONS = {"wa.request-workflow-information.create",
            "docm.document-comments.create"};

    @Test
    void up_addsColumnsTablesAndConstraints_thatBite() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "46");
            Ids ids;
            try (Connection c = connect(url)) {
                ids = seed(c);
            }
            migrate(url, "50");
            try (Connection c = connect(url)) {
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table).isTrue();
                }
                assertThat(columnDefault(c, "workflow_stages", "allow_info_requests")).isEqualTo("true");
                assertThat(columnDefault(c, "workflow_tasks", "sla_paused_seconds")).isEqualTo("0");
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_tasks'"
                        + " and column_name in ('sla_paused_at', 'escalated_to')")).isEqualTo(2);
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_actions'"
                        + " and column_name = 'info_request_id'")).isEqualTo(1);
                // existing rows took the defaults
                assertThat(count(c, "select count(*) from workflow_stages where allow_info_requests")).isEqualTo(1);

                // decision 20: the re-created partial indexes include awaiting_info
                assertThat(indexDefinition(c, "ix_workflow_tasks_open_assignee")).contains("awaiting_info")
                        .contains("pending").contains("claimed");
                assertThat(indexDefinition(c, "ix_workflow_tasks_claimed_by")).contains("awaiting_info");
                assertThat(indexDefinition(c, "ix_workflow_tasks_sla_due")).contains("due_at")
                        .doesNotContain("awaiting_info");
                assertThat(indexes(c)).contains("ix_workflow_tasks_delegated_to", "ix_workflow_tasks_escalated_to",
                        "ix_workflow_info_requests_task_open", "uq_workflow_supporting_documents_instance_version",
                        "ix_comment_mentions_user", "ix_document_comments_document");
                assertThat(constraints(c, "workflow_info_requests")).contains("fk_workflow_info_requests_task",
                        "fk_workflow_info_requests_stage_instance", "fk_workflow_info_requests_instance");
                assertThat(constraints(c, "workflow_supporting_documents"))
                        .contains("fk_workflow_supporting_documents_version", "fk_workflow_supporting_documents_document");
                for (String code : PERMISSIONS) {
                    assertThat(count(c, "select count(*) from permissions where permission_code = '" + code + "'"))
                            .as(code).isEqualTo(1);
                }

                // A pool task can't wait on information without a holder (decision 12).
                assertThatThrownBy(() -> exec(c, "update workflow_tasks set status = 'awaiting_info' where task_id = '"
                        + ids.task2() + "'"))
                        .hasMessageContaining("ck_workflow_tasks_awaiting_info_holder");
                exec(c, "update workflow_tasks set status = 'awaiting_info', claimed_by = '" + ids.user()
                        + "', sla_paused_at = now() where task_id = '" + ids.task2() + "'");
                // The SLA clock is paused only while awaiting information.
                assertThatThrownBy(() -> exec(c, "update workflow_tasks set status = 'pending' where task_id = '"
                        + ids.task2() + "'"))
                        .hasMessageContaining("ck_workflow_tasks_sla_paused_at");
                exec(c, "update workflow_tasks set status = 'claimed', sla_paused_at = null, sla_paused_seconds = 60"
                        + " where task_id = '" + ids.task2() + "'");
                // Delegation columns move together, never to the same person.
                assertThatThrownBy(() -> exec(c, "update workflow_tasks set delegated_to = '" + ids.user()
                        + "' where task_id = '" + ids.task() + "'"))
                        .hasMessageContaining("ck_workflow_tasks_delegation");
                assertThatThrownBy(() -> exec(c, "update workflow_tasks set delegated_from = '" + ids.user()
                        + "', delegated_to = '" + ids.user() + "', delegated_at = now() where task_id = '" + ids.task() + "'"))
                        .hasMessageContaining("ck_workflow_tasks_delegation");
                assertThatThrownBy(() -> exec(c, "update workflow_tasks set escalated_to = '" + ids.user()
                        + "' where task_id = '" + ids.task() + "'"))
                        .hasMessageContaining("ck_workflow_tasks_escalation");

                // Information requests
                String other = insertUser(c, ids.tenant());
                String request = uuid();
                exec(c, "insert into workflow_info_requests (info_request_id, tenant_id, instance_id, stage_instance_id,"
                        + " task_id, requested_by, requested_from, message) values ('" + request + "', '" + ids.tenant()
                        + "', '" + ids.instance() + "', '" + ids.stageInstance() + "', '" + ids.task2() + "', '"
                        + ids.user() + "', '" + other + "', 'Please send the signed annex')");
                assertThatThrownBy(() -> exec(c, "update workflow_info_requests set status = 'declined', responded_at = now(),"
                        + " responded_by = '" + other + "' where info_request_id = '" + request + "'"))
                        .hasMessageContaining("ck_workflow_info_requests_declined");
                assertThatThrownBy(() -> exec(c, "update workflow_info_requests set status = 'answered'"
                        + " where info_request_id = '" + request + "'"))
                        .hasMessageContaining("ck_workflow_info_requests_responded");
                assertThatThrownBy(() -> exec(c, "insert into workflow_info_requests (tenant_id, instance_id,"
                        + " stage_instance_id, task_id, requested_by, requested_from, message) values ('" + ids.tenant()
                        + "', '" + ids.instance() + "', '" + ids.stageInstance() + "', '" + ids.task2() + "', '"
                        + ids.user() + "', '" + ids.user() + "', 'x')"))
                        .hasMessageContaining("ck_workflow_info_requests_not_self");

                // Supporting documents: one live row per version of an instance.
                String insertSupporting = "insert into workflow_supporting_documents (tenant_id, instance_id,"
                        + " info_request_id, document_id, document_version_id, source, added_by) values ('" + ids.tenant()
                        + "', '" + ids.instance() + "', '" + request + "', '" + ids.document() + "', '" + ids.version()
                        + "', 'linked_existing', '" + other + "')";
                exec(c, insertSupporting);
                assertThatThrownBy(() -> exec(c, insertSupporting))
                        .hasMessageContaining("uq_workflow_supporting_documents_instance_version");

                // A9: the widened timeline types, linked to the request.
                exec(c, "insert into workflow_actions (tenant_id, instance_id, task_id, info_request_id, action_type)"
                        + " values ('" + ids.tenant() + "', '" + ids.instance() + "', '" + ids.task2() + "', '" + request
                        + "', 'supporting_document_added')");

                // Comments and mentions
                String comment = uuid();
                exec(c, "insert into document_comments (comment_id, tenant_id, document_id, workflow_instance_id, body,"
                        + " author_user_id) values ('" + comment + "', '" + ids.tenant() + "', '" + ids.document() + "', '"
                        + ids.instance() + "', 'Looks fine', '" + ids.user() + "')");
                assertThatThrownBy(() -> exec(c, "insert into document_comments (tenant_id, document_id, body,"
                        + " author_user_id) values ('" + ids.tenant() + "', '" + ids.document() + "', '   ', '"
                        + ids.user() + "')"))
                        .hasMessageContaining("ck_document_comments_body");
                exec(c, "insert into comment_mentions (tenant_id, comment_id, mentioned_user_id, had_document_access)"
                        + " values ('" + ids.tenant() + "', '" + comment + "', '" + other + "', false)");
                assertThatThrownBy(() -> exec(c, "insert into comment_mentions (tenant_id, comment_id, mentioned_user_id,"
                        + " had_document_access) values ('" + ids.tenant() + "', '" + comment + "', '" + other + "', true)"))
                        .hasMessageContaining("uq_comment_mentions_comment_user");
            }
        });
    }

    @Test
    void down_removesEverythingCleanly_andUpCanBeReapplied() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "50");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                for (String script : ROLLBACKS) {
                    s.execute(read(script));
                }
                s.execute("delete from flyway_schema_history where version in ('47', '48', '49', '50')");
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table + " after rollback").isFalse();
                }
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_tasks'"
                        + " and column_name in ('sla_paused_at', 'sla_paused_seconds', 'escalated_to')")).isZero();
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'workflow_stages'"
                        + " and column_name = 'allow_info_requests'")).isZero();
                assertThat(indexDefinition(c, "ix_workflow_tasks_open_assignee")).doesNotContain("awaiting_info");
                assertThat(indexDefinition(c, "ix_workflow_tasks_claimed_by")).doesNotContain("awaiting_info");
                for (String code : PERMISSIONS) {
                    assertThat(count(c, "select count(*) from permissions where permission_code = '" + code + "'"))
                            .as(code).isZero();
                }
            }
            migrate(url, "50");
            try (Connection c = connect(url)) {
                for (String table : TABLES) {
                    assertThat(tableExists(c, table)).as(table + " after re-applying").isTrue();
                }
                assertThat(count(c, "select count(*) from flyway_schema_history where version in ('47','48','49','50')"
                        + " and success")).isEqualTo(4);
            }
        });
    }

    private static String insertUser(Connection c, String tenant) throws SQLException {
        String user = uuid();
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + user + "', '" + tenant + "', '" + uuid() + "', 'v47-" + user + "@test.invalid', 'v47-"
                + user + "', 'V47', 'V47', 'active')");
        return user;
    }

    private static String columnDefault(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select column_default from information_schema.columns where table_name = '" + table
                        + "' and column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
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
                + "', 'V47 tenant', 'v47-" + tenant + ".invalid', 'standard', 'active')");
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + user + "', '" + tenant + "', '" + uuid() + "', 'v47-" + user + "@test.invalid', 'v47-"
                + user + "', 'V47', 'V47', 'active')");
        exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('" + type + "', '"
                + tenant + "', 'CONTRACT', 'Contract')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + location + "', '" + tenant + "', 'v47', 'FILE_SYSTEM', 'document', '/tmp/v47', 'active')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + staging + "', '" + tenant + "', 'v47-staging', 'FILE_SYSTEM', 'staging', '/tmp/v47s', 'active')");
        exec(c, "insert into scan_engines (scan_engine_id, tenant_id, name, provider, scan_mode, timeout_seconds,"
                + " max_file_size_bytes, status) values ('" + engine + "', '" + tenant + "', 'v47', 'CLAMAV',"
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
                + engine + "', '" + version + "', '" + staging + "', 'a.pdf', 'h', 1, 'application/pdf', 'clean', 'v47',"
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
        try (InputStream in = V47ToV50WorkflowPhase2MigrationTest.class.getResourceAsStream(script)) {
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
        String name = "dms_v47_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
