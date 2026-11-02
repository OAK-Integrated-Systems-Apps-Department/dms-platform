package com.oak.dms.ownership;

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
 * V113-V115 (Document Ownership and Transfer: owner department/state, holding owner, batches and the
 * append-only history, the owner-department backfill, the three notification categories) up and down on a
 * throwaway database - same harness as {@link com.oak.dms.relationship.V106ToV109RelationshipMigrationTest}.
 */
class V113ToV115OwnershipMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String[] ROLLBACKS = {
            "/db/rollback/V115__seed_ownership_notification_categories.rollback.sql",
            "/db/rollback/V114__backfill_document_owner_department.rollback.sql",
            "/db/rollback/V113__create_document_ownership.rollback.sql"};
    private static final String CATEGORIES =
            "('document.ownership.received','document.ownership.released','document.ownership.holding')";

    @Test
    void up_backfillsOwnerDepartmentAndConstraintsBite() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "109");
            Ids a;
            Ids other;
            String dept;
            try (Connection c = connect(url)) {
                a = seed(c);
                other = seed(c);
                dept = uuid();
                exec(c, "insert into departments (department_id, tenant_id, name, code) values ('" + dept + "', '"
                        + a.tenant() + "', 'Finance', 'FIN-" + dept.substring(0, 6) + "')");
                // first document's owner is in the department, the second's is not
                exec(c, "update users set department_id = '" + dept + "' where user_id = '" + a.user() + "'");
                String loner = uuid();
                exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                        + " values ('" + loner + "', '" + a.tenant() + "', '" + uuid() + "', 'l-" + loner + "@test.invalid', 'l-"
                        + loner + "', 'L', 'L', 'active')");
                exec(c, "update documents set owner_user_id = '" + loner + "' where document_id = '" + a.documents()[1] + "'");
            }
            migrate(url, "115");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "ownership_transfers")).isTrue();
                assertThat(tableExists(c, "ownership_transfer_batches")).isTrue();

                // V114 backfill: owner department = the owner's department, null when none; state defaults.
                assertThat(count(c, "select count(*) from documents where document_id = '" + a.documents()[0]
                        + "' and owner_department_id = '" + dept + "' and ownership_state = 'assigned'")).isEqualTo(1);
                assertThat(count(c, "select count(*) from documents where document_id = '" + a.documents()[1]
                        + "' and owner_department_id is null and ownership_state = 'assigned'")).isEqualTo(1);
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'departments'"
                        + " and column_name = 'holding_owner_user_id'")).isEqualTo(1);

                // documents constraints
                assertThatThrownBy(() -> exec(c, "update documents set ownership_state = 'parked' where document_id = '"
                        + a.documents()[0] + "'")).hasMessageContaining("ck_documents_ownership_state");
                assertThatThrownBy(() -> exec(c, "update documents set owner_department_id = '" + uuid()
                        + "' where document_id = '" + a.documents()[0] + "'"))
                        .hasMessageContaining("fk_documents_owner_department");
                // a department of another tenant is rejected by the composite FK
                String foreignDept = uuid();
                exec(c, "insert into departments (department_id, tenant_id, name, code) values ('" + foreignDept + "', '"
                        + other.tenant() + "', 'Other', 'OTH-" + foreignDept.substring(0, 6) + "')");
                assertThatThrownBy(() -> exec(c, "update documents set owner_department_id = '" + foreignDept
                        + "' where document_id = '" + a.documents()[0] + "'"))
                        .hasMessageContaining("fk_documents_owner_department");

                // batch constraints
                String batch = uuid();
                assertThatThrownBy(() -> insertBatch(c, a, batch, "weekly", "manual", 1, 1, 0))
                        .hasMessageContaining("ck_ownership_batches_kind");
                assertThatThrownBy(() -> insertBatch(c, a, batch, "bulk", "whim", 1, 1, 0))
                        .hasMessageContaining("ck_ownership_batches_reason");
                assertThatThrownBy(() -> insertBatch(c, a, batch, "bulk", "manual", 5, 1, 1))
                        .hasMessageContaining("ck_ownership_batches_counts");
                insertBatch(c, a, batch, "bulk", "manual", 2, 2, 0);

                // history: bad reason, cross-tenant document, good rows
                assertThatThrownBy(() -> insertTransfer(c, a.tenant(), a.documents()[0], a.user(), batch, "whim"))
                        .hasMessageContaining("ck_ownership_transfers_reason");
                assertThatThrownBy(() -> insertTransfer(c, other.tenant(), a.documents()[0], a.user(), null, "manual"))
                        .hasMessageContaining("fk_ownership_transfers_document");
                String row = insertTransfer(c, a.tenant(), a.documents()[0], a.user(), batch, "manual");
                insertTransfer(c, a.tenant(), a.documents()[0], a.user(), null, "holding");

                // append-only: UPDATE and DELETE are rejected even for the owning role
                assertThatThrownBy(() -> exec(c, "update ownership_transfers set note = 'x' where transfer_id = '" + row + "'"))
                        .hasMessageContaining("append-only");
                assertThatThrownBy(() -> exec(c, "delete from ownership_transfers where transfer_id = '" + row + "'"))
                        .hasMessageContaining("append-only");
                assertThat(count(c, "select count(*) from ownership_transfers")).isEqualTo(2);

                // V115 categories
                assertThat(count(c, "select count(*) from notification_categories where category_code in " + CATEGORIES
                        + " and module = 'documents'")).isEqualTo(3);
                assertThat(count(c, "select count(*) from notification_categories where category_code = 'document.ownership.released'"
                        + " and tier = 'optional' and not default_email_enabled")).isEqualTo(1);
                assertThat(count(c, "select count(*) from notification_categories where category_code in "
                        + "('document.ownership.received','document.ownership.holding') and tier = 'standard' and default_email_enabled"))
                        .isEqualTo(2);

                Set<String> indexes = indexes(c);
                assertThat(indexes).contains("ix_ownership_transfers_document", "ix_ownership_transfers_batch",
                        "ix_documents_tenant_owner", "ix_documents_owner_department", "ix_documents_holding");
            }
        });
    }

    @Test
    void down_removesEverythingCleanly_andUpCanBeReapplied() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "109");
            try (Connection c = connect(url)) {
                seed(c);
            }
            migrate(url, "115");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                for (String script : ROLLBACKS) {
                    s.execute(read(script));
                }
                s.execute("delete from flyway_schema_history where version in ('113', '114', '115')");
                assertThat(tableExists(c, "ownership_transfers")).isFalse();
                assertThat(tableExists(c, "ownership_transfer_batches")).isFalse();
                assertThat(count(c, "select count(*) from information_schema.columns where column_name in"
                        + " ('owner_department_id','ownership_state','holding_owner_user_id')")).isZero();
                assertThat(count(c, "select count(*) from notification_categories where category_code in " + CATEGORIES))
                        .isZero();
            }
            migrate(url, "115");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "ownership_transfers")).isTrue();
                assertThat(count(c, "select count(*) from notification_categories where category_code in " + CATEGORIES))
                        .isEqualTo(3);
                assertThat(count(c, "select count(*) from documents where ownership_state = 'assigned'")).isEqualTo(2);
                assertThat(count(c, "select count(*) from flyway_schema_history where version in ('113','114','115')"
                        + " and success")).isEqualTo(3);
            }
        });
    }

    // ---------------------------------------------------------------- seed

    private record Ids(String tenant, String user, String[] documents) {
    }

    /** A tenant with a user and two documents (each with a clean version and scan result). */
    private static Ids seed(Connection c) throws SQLException {
        String tenant = uuid();
        String user = uuid();
        String type = uuid();
        String location = uuid();
        String staging = uuid();
        String engine = uuid();
        exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                + "', 'V113 tenant', 'v113-" + tenant + ".invalid', 'standard', 'active')");
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + user + "', '" + tenant + "', '" + uuid() + "', 'v113-" + user + "@test.invalid', 'v113-"
                + user + "', 'V113', 'V113', 'active')");
        exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('" + type + "', '"
                + tenant + "', 'CONTRACT', 'Contract')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + location + "', '" + tenant + "', 'v113', 'FILE_SYSTEM', 'document', '/tmp/v113', 'active')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + staging + "', '" + tenant + "', 'v113-staging', 'FILE_SYSTEM', 'staging', '/tmp/v113s', 'active')");
        exec(c, "insert into scan_engines (scan_engine_id, tenant_id, name, provider, scan_mode, timeout_seconds,"
                + " max_file_size_bytes, status) values ('" + engine + "', '" + tenant + "', 'v113', 'CLAMAV',"
                + " 'pre_store_stream', 60, 1000, 'active')");
        String[] documents = {uuid(), uuid()};
        c.setAutoCommit(false);
        for (String document : documents) {
            String version = uuid();
            exec(c, "insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id)"
                    + " values ('" + document + "', '" + tenant + "', '" + type + "', 'Doc', '" + version + "', '" + user + "')");
            exec(c, "insert into document_versions (version_id, document_id, tenant_id, version_number, major_version,"
                    + " minor_version, storage_location_id, storage_object_key, original_filename, mime_type, file_hash,"
                    + " size_bytes, created_reason, scan_status) values ('" + version + "', '" + document + "', '" + tenant
                    + "', 1, 1, 0, '" + location + "', 'k" + version + "', 'a.pdf', 'application/pdf', 'h', 1, 'initial_upload', 'clean')");
            exec(c, "insert into scan_results (tenant_id, scan_engine_id, document_version_id, staging_location_id,"
                    + " original_filename, file_hash, size_bytes, mime_type, status, engine_name, scan_mode, completed_at,"
                    + " uploaded_by, ingestion_kind, created_reason, document_type_id, title) values ('" + tenant + "', '"
                    + engine + "', '" + version + "', '" + staging + "', 'a.pdf', 'h', 1, 'application/pdf', 'clean', 'v113',"
                    + " 'pre_store_stream', now(), '" + user + "', 'new_document', 'initial_upload', '" + type + "', 'Doc')");
        }
        c.commit();
        c.setAutoCommit(true);
        return new Ids(tenant, user, documents);
    }

    private static void insertBatch(Connection c, Ids ids, String batch, String kind, String reason, int total,
                                    int done, int skipped) throws SQLException {
        exec(c, "insert into ownership_transfer_batches (batch_id, tenant_id, kind, new_owner_user_id, reason,"
                + " total_count, transferred_count, skipped_count, created_by) values ('" + batch + "', '" + ids.tenant()
                + "', '" + kind + "', '" + ids.user() + "', '" + reason + "', " + total + ", " + done + ", " + skipped
                + ", '" + ids.user() + "')");
    }

    private static String insertTransfer(Connection c, String tenant, String document, String user, String batch,
                                         String reason) throws SQLException {
        String id = uuid();
        exec(c, "insert into ownership_transfers (transfer_id, tenant_id, document_id, previous_owner_user_id,"
                + " new_owner_user_id, reason, batch_id, actor_user_id) values ('" + id + "', '" + tenant + "', '"
                + document + "', '" + user + "', '" + user + "', '" + reason + "', "
                + (batch == null ? "null" : "'" + batch + "'") + ", '" + user + "')");
        return id;
    }

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
        try (InputStream in = V113ToV115OwnershipMigrationTest.class.getResourceAsStream(script)) {
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
        String name = "dms_v113_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
