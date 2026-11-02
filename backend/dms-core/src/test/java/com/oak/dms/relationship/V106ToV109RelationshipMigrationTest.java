package com.oak.dms.relationship;

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
 * V106-V109 (2.8 Document Relationships: tables, the 10 system types for existing tenants,
 * the duplicate-origin column, the notification category) up and down on a throwaway
 * database - same harness as {@link com.oak.dms.notification.V103ToV105NotificationMigrationTest}.
 * Proves the CHECKs and unique indexes bite, the composite FKs keep a link inside its
 * tenant, an existing tenant is backfilled, and the four rollbacks reverse cleanly.
 */
class V106ToV109RelationshipMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String[] ROLLBACKS = {
            "/db/rollback/V109__seed_relationship_notification_category.rollback.sql",
            "/db/rollback/V108__link_duplicate_origin_columns.rollback.sql",
            "/db/rollback/V107__seed_relationship_types_for_existing_tenants.rollback.sql",
            "/db/rollback/V106__create_relationship_types_and_document_relationships.rollback.sql"};
    private static final Set<String> TYPE_CODES = Set.of("PARENT_CHILD", "MAIN_ATTACHMENT", "CONTRACT_AMENDMENT",
            "SUPERSEDES", "REPLACES", "INVOICE_SUPPORTING", "REPORT_EVIDENCE", "ORIGINAL_TRANSLATION",
            "DUPLICATE_OF", "RELATED");

    @Test
    void up_backfillsTypesAndConstraintsBite() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "105");
            Ids a;
            Ids b;
            try (Connection c = connect(url)) {
                a = seed(c);
                b = seed(c);
            }
            migrate(url, "109");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "relationship_types")).isTrue();
                assertThat(tableExists(c, "document_relationships")).isTrue();

                // V107 backfilled both existing tenants with the 10 system types.
                for (Ids ids : new Ids[]{a, b}) {
                    assertThat(count(c, "select count(*) from relationship_types where tenant_id = '" + ids.tenant()
                            + "' and is_system and is_enabled and not is_deleted")).isEqualTo(10);
                }
                Set<String> codes = new HashSet<>();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                        "select code from relationship_types where tenant_id = '" + a.tenant() + "'")) {
                    while (rs.next()) {
                        codes.add(rs.getString(1));
                    }
                }
                assertThat(codes).isEqualTo(TYPE_CODES);
                assertThat(count(c, "select count(*) from relationship_types where tenant_id = '" + a.tenant()
                        + "' and prevents_cycles and code in ('PARENT_CHILD','SUPERSEDES','REPLACES')")).isEqualTo(3);
                assertThat(count(c, "select count(*) from relationship_types where tenant_id = '" + a.tenant()
                        + "' and code = 'RELATED' and directionality = 'symmetric'")).isEqualTo(1);

                // V108 columns, V109 category.
                assertThat(count(c, "select count(*) from information_schema.columns where column_name = 'duplicate_of_document_id'"
                        + " and table_name = 'scan_results'")).isEqualTo(1);
                assertThat(count(c, "select count(*) from notification_categories where category_code = 'document.relationship-added'"
                        + " and tier = 'optional' and not default_email_enabled")).isEqualTo(1);

                // Type constraints.
                assertThatThrownBy(() -> exec(c, "insert into relationship_types (tenant_id, code, category, label,"
                        + " inverse_label, directionality) values ('" + a.tenant() + "', 'X', 'c', 'l', 'i', 'sideways')"))
                        .hasMessageContaining("ck_relationship_types_directionality");
                assertThatThrownBy(() -> exec(c, "insert into relationship_types (tenant_id, code, category, label,"
                        + " inverse_label, directionality, prevents_cycles) values ('" + a.tenant()
                        + "', 'X', 'c', 'l', 'l', 'symmetric', true)"))
                        .hasMessageContaining("ck_relationship_types_symmetric_cycles");
                assertThatThrownBy(() -> exec(c, "insert into relationship_types (tenant_id, code, category, label,"
                        + " inverse_label, directionality) values ('" + a.tenant() + "', 'SUPERSEDES', 'c', 'l', 'i', 'directional')"))
                        .hasMessageContaining("uq_relationship_types_tenant_code");
                assertThatThrownBy(() -> exec(c, "update relationship_types set is_deleted = true where tenant_id = '"
                        + a.tenant() + "' and code = 'SUPERSEDES'"))
                        .hasMessageContaining("ck_relationship_types_system_not_deleted");

                String supersedes = typeId(c, a.tenant(), "SUPERSEDES");
                String d1 = a.documents()[0];
                String d2 = a.documents()[1];

                // Self-link, bad origin, bad removed combination.
                assertThatThrownBy(() -> insertRel(c, a.tenant(), supersedes, d1, d1, "manual", ""))
                        .hasMessageContaining("ck_document_relationships_distinct");
                assertThatThrownBy(() -> insertRel(c, a.tenant(), supersedes, d1, d2, "telepathy", ""))
                        .hasMessageContaining("ck_document_relationships_origin");
                assertThatThrownBy(() -> insertRel(c, a.tenant(), supersedes, d1, d2, "manual", ", removed_at"
                        , ", now()"))
                        .hasMessageContaining("ck_document_relationships_removed");

                // A good link; the same pair again is rejected, a different type or direction is not.
                String first = insertRel(c, a.tenant(), supersedes, d1, d2, "manual", "");
                assertThatThrownBy(() -> insertRel(c, a.tenant(), supersedes, d1, d2, "manual", ""))
                        .hasMessageContaining("uq_document_relationships_active");
                insertRel(c, a.tenant(), supersedes, d2, d1, "manual", "");
                insertRel(c, a.tenant(), typeId(c, a.tenant(), "REPLACES"), d1, d2, "manual", "");

                // Removed rows leave the unique index: the pair can be linked again.
                exec(c, "update document_relationships set removed_at = now(), removed_reason = 'user_removed'"
                        + " where relationship_id = '" + first + "'");
                insertRel(c, a.tenant(), supersedes, d1, d2, "manual", "");

                // Composite FKs: another tenant can't link this tenant's documents, nor use its types.
                assertThatThrownBy(() -> insertRel(c, b.tenant(), typeId(c, b.tenant(), "SUPERSEDES"), d1, d2, "manual", ""))
                        .hasMessageContaining("fk_document_relationships_source");
                assertThatThrownBy(() -> insertRel(c, a.tenant(), typeId(c, b.tenant(), "SUPERSEDES"), d1, d2, "manual", ""))
                        .hasMessageContaining("fk_document_relationships_type");

                assertThat(indexes(c)).contains("ix_document_relationships_source", "ix_document_relationships_target",
                        "ix_document_relationships_type", "uq_document_relationships_active",
                        "uq_relationship_types_tenant_code");
            }
        });
    }

    @Test
    void down_removesEverythingCleanly_andUpCanBeReapplied() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "105");
            try (Connection c = connect(url)) {
                seed(c);
            }
            migrate(url, "109");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                for (String script : ROLLBACKS) {
                    s.execute(read(script));
                }
                s.execute("delete from flyway_schema_history where version in ('106', '107', '108', '109')");
                assertThat(tableExists(c, "relationship_types")).isFalse();
                assertThat(tableExists(c, "document_relationships")).isFalse();
                assertThat(count(c, "select count(*) from information_schema.columns where column_name = 'duplicate_of_document_id'"))
                        .isZero();
                assertThat(count(c, "select count(*) from notification_categories where category_code = 'document.relationship-added'"))
                        .isZero();
            }
            migrate(url, "109");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "document_relationships")).isTrue();
                assertThat(count(c, "select count(*) from relationship_types")).isEqualTo(10);
                assertThat(count(c, "select count(*) from flyway_schema_history where version in ('106','107','108','109')"
                        + " and success")).isEqualTo(4);
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
                + "', 'V111 tenant', 'v106-" + tenant + ".invalid', 'standard', 'active')");
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + user + "', '" + tenant + "', '" + uuid() + "', 'v106-" + user + "@test.invalid', 'v106-"
                + user + "', 'V111', 'V111', 'active')");
        exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('" + type + "', '"
                + tenant + "', 'CONTRACT', 'Contract')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + location + "', '" + tenant + "', 'v106', 'FILE_SYSTEM', 'document', '/tmp/v106', 'active')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + staging + "', '" + tenant + "', 'v106-staging', 'FILE_SYSTEM', 'staging', '/tmp/v106s', 'active')");
        exec(c, "insert into scan_engines (scan_engine_id, tenant_id, name, provider, scan_mode, timeout_seconds,"
                + " max_file_size_bytes, status) values ('" + engine + "', '" + tenant + "', 'v106', 'CLAMAV',"
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
                    + engine + "', '" + version + "', '" + staging + "', 'a.pdf', 'h', 1, 'application/pdf', 'clean', 'v106',"
                    + " 'pre_store_stream', now(), '" + user + "', 'new_document', 'initial_upload', '" + type + "', 'Doc')");
        }
        c.commit();
        c.setAutoCommit(true);
        return new Ids(tenant, user, documents);
    }

    private static String insertRel(Connection c, String tenant, String type, String source, String target,
                                    String origin, String extraColumns) throws SQLException {
        return insertRel(c, tenant, type, source, target, origin, extraColumns, "");
    }

    private static String insertRel(Connection c, String tenant, String type, String source, String target,
                                    String origin, String extraColumns, String extraValues) throws SQLException {
        String id = uuid();
        exec(c, "insert into document_relationships (relationship_id, tenant_id, relationship_type_id, source_document_id,"
                + " target_document_id, origin" + extraColumns + ") values ('" + id + "', '" + tenant + "', '" + type + "', '"
                + source + "', '" + target + "', '" + origin + "'" + extraValues + ")");
        return id;
    }

    // ---------------------------------------------------------------- helpers

    private static String typeId(Connection c, String tenant, String code) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select relationship_type_id from relationship_types where tenant_id = '" + tenant + "' and code = '" + code + "'")) {
            rs.next();
            return rs.getString(1);
        }
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
        try (InputStream in = V106ToV109RelationshipMigrationTest.class.getResourceAsStream(script)) {
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
        String name = "dms_v106_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
