package com.oak.dms.metadata;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static com.oak.dms.metadata.support.StarterCatalogAssertions.assertExactStarterCatalog;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.catalogSnapshot;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.count;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.expectedElements;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.schemaElements;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V37 removes the V24 platform tenant / system user together with the
 * V30 catalogue seeded into it, backfills the starter catalogue into every
 * real tenant without touching that tenant's own rows, and refuses to run
 * when the platform tenant or system user owns anything else. Same
 * throwaway-database harness as {@link V36MigrationIT}.
 */
class V37MigrationIT {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    private static final String PLATFORM_TENANT = "00000000-0000-0000-0000-000000000000";
    private static final String SYSTEM_USER = "00000000-0000-0000-0000-000000000001";

    @Test
    void freshDatabase_removesPlatformTenantSystemUserAndEveryReferenceToThem() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "36");
            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from metadata_fields where tenant_id = '" + PLATFORM_TENANT + "'"))
                        .isEqualTo(25);
            }

            migrate(url, "37");

            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from tenants where tenant_id = '" + PLATFORM_TENANT + "'")).isZero();
                assertThat(count(c, "select count(*) from users where user_id = '" + SYSTEM_USER + "'")).isZero();
                assertThat(count(c, "select count(*) from tenants")).isZero();
                for (String[] fk : foreignKeyColumns(c, "tenants")) {
                    assertThat(count(c, "select count(*) from " + fk[0] + " where " + fk[1] + " = '" + PLATFORM_TENANT + "'"))
                            .as(fk[0] + "." + fk[1]).isZero();
                }
                for (String[] fk : foreignKeyColumns(c, "users")) {
                    assertThat(count(c, "select count(*) from " + fk[0] + " where " + fk[1] + " = '" + SYSTEM_USER + "'"))
                            .as(fk[0] + "." + fk[1]).isZero();
                }
                for (String table : List.of("metadata_fields", "metadata_field_options", "document_types",
                        "document_type_schemas", "document_type_fields")) {
                    assertThat(count(c, "select count(*) from " + table)).as(table).isZero();
                }
                // The legacy table is frozen again after the delete.
                assertThatThrownBy(() -> {
                    try (Statement s = c.createStatement()) {
                        s.execute("delete from document_type_fields where false");
                    }
                }).hasMessageContaining("document_type_fields is read-only since V36");
            }
        });
    }

    @Test
    void emptyTenantCreatedBeforeV37_getsExactlyTheStarterCatalogue() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "36");
            UUID tenantId = insertTenant(url);

            migrate(url, "37");

            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from metadata_fields where tenant_id = ?", tenantId)).isEqualTo(25);
                assertThat(count(c, "select count(*) from metadata_field_options where tenant_id = ?", tenantId)).isEqualTo(12);
                assertThat(count(c, "select count(*) from document_types where tenant_id = ?", tenantId)).isEqualTo(4);
                assertThat(count(c, "select count(*) from document_type_schemas where tenant_id = ?", tenantId)).isEqualTo(4);
                assertExactStarterCatalog(c, tenantId, null);
            }
        });
    }

    @Test
    void tenantWithItsOwnData_isBackfilledAroundItWithoutTouchingIt() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "36");
            UUID tenantId = insertTenant(url);
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                s.execute("""
                        insert into document_types (tenant_id, type_code, type_name, display_order) values
                            ('%1$s', 'HR', 'Human resources', 10),
                            ('%1$s', 'LEGAL', 'Legal', 20),
                            ('%1$s', 'INVOICE', 'Our own invoices', 30)""".formatted(tenantId));
                s.execute("""
                        insert into document_type_schemas (tenant_id, document_type_id, schema_version)
                        select tenant_id, document_type_id, 1 from document_types where tenant_id = '%s'""".formatted(tenantId));
                s.execute("""
                        insert into metadata_fields (tenant_id, field_key, label, data_type, status, is_deleted) values
                            ('%1$s', 'currency', 'Our currency', 'dropdown', 'active', false),
                            ('%1$s', 'description', 'Old description', 'long_text', 'deprecated', false),
                            ('%1$s', 'tags', 'Deleted tags', 'multi_select', 'active', true)""".formatted(tenantId));
                s.execute("""
                        insert into metadata_field_options (tenant_id, field_id, option_value, option_label, display_order)
                        select tenant_id, field_id, v.val, v.val, v.ord
                        from metadata_fields, (values ('AUD', 10), ('NZD', 20)) as v(val, ord)
                        where tenant_id = '%s' and field_key = 'currency'""".formatted(tenantId));
            }
            List<String> before;
            try (Connection c = connect(url)) {
                before = catalogSnapshot(c, tenantId);
            }

            migrate(url, "37");

            try (Connection c = connect(url)) {
                assertThat(catalogSnapshot(c, tenantId)).containsAll(before);

                assertThat(strings(c, "select type_code || ':' || display_order from document_types "
                        + "where tenant_id = ? and is_deleted = false order by display_order", tenantId))
                        .containsExactly("HR:10", "LEGAL:20", "INVOICE:30", "CONTRACT:40", "POLICY:50", "GENERAL:60");
                assertThat(count(c, "select count(*) from document_types where tenant_id = ? and type_code = 'INVOICE'",
                        tenantId)).isEqualTo(1);

                // 25 starter keys minus currency and description; tags is recreated.
                assertThat(count(c, "select count(*) from metadata_fields where tenant_id = ?", tenantId))
                        .isEqualTo(3 + 23);
                assertThat(count(c, "select count(*) from metadata_fields where tenant_id = ? and field_key = 'tags' "
                        + "and is_deleted = false and status = 'active'", tenantId)).isEqualTo(1);

                assertThat(strings(c, """
                        select o.option_value from metadata_field_options o join metadata_fields f on f.field_id = o.field_id
                        where f.tenant_id = ? and f.field_key = 'currency' order by o.display_order""", tenantId))
                        .containsExactly("AUD", "NZD");
                // Only the recreated classification_level / retention_class got options.
                assertThat(count(c, "select count(*) from metadata_field_options where tenant_id = ?", tenantId))
                        .isEqualTo(2 + 8);

                assertThat(count(c, "select count(*) from document_type_schemas where tenant_id = ?", tenantId))
                        .isEqualTo(3 + 3);
                assertThat(schemaElements(c, tenantId, List.of("CONTRACT", "POLICY", "GENERAL")))
                        .containsExactlyElementsOf(expectedElements(List.of("CONTRACT", "POLICY", "GENERAL"),
                                List.of("description")));
            }
        });
    }

    @Test
    void tenantThatAlreadyHasTheFullCatalogue_isLeftUnchanged() throws Exception {
        withScratchDatabase(url -> {
            // Stop before V30 so it seeds this tenant, as it did for tenants
            // that existed when it first ran.
            migrate(url, "29");
            UUID tenantId = insertTenant(url);
            migrate(url, "36");
            List<String> before;
            try (Connection c = connect(url)) {
                before = catalogSnapshot(c, tenantId);
                assertThat(count(c, "select count(*) from metadata_fields where tenant_id = ?", tenantId)).isEqualTo(25);
            }

            migrate(url, "37");

            try (Connection c = connect(url)) {
                assertThat(catalogSnapshot(c, tenantId)).isEqualTo(before);
            }
        });
    }

    @Test
    void nullableReferencesToTheSystemUser_areCleared() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "36");
            UUID tenantId = insertTenant(url);
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                s.execute("insert into roles (tenant_id, role_name, created_by, updated_by) values ('"
                        + tenantId + "', 'Clerk', '" + SYSTEM_USER + "', '" + SYSTEM_USER + "')");
                s.execute("insert into document_types (tenant_id, type_code, type_name, display_order, created_by) values ('"
                        + tenantId + "', 'MEMO', 'Memo', 10, '" + SYSTEM_USER + "')");
                s.execute("update tenants set created_by = '" + SYSTEM_USER + "' where tenant_id = '" + tenantId + "'");
            }

            migrate(url, "37");

            try (Connection c = connect(url)) {
                assertThat(strings(c, "select coalesce(created_by::text, 'null') || ',' || coalesce(updated_by::text, 'null') "
                        + "from roles where tenant_id = ?", tenantId)).containsExactly("null,null");
                assertThat(strings(c, "select coalesce(created_by::text, 'null') || ':' || display_order from document_types "
                        + "where tenant_id = ? and type_code = 'MEMO'", tenantId)).containsExactly("null:10");
                assertThat(strings(c, "select coalesce(created_by::text, 'null') from tenants where tenant_id = ?", tenantId))
                        .containsExactly("null");
            }
        });
    }

    @Test
    void abortsWhenAnAuditEventBelongsToThePlatformTenant() throws Exception {
        assertAborts("""
                insert into audit_events (tenant_id, actor_type, action, module, object_type, outcome, occurred_at, record_hash)
                values ('%s', 'SYSTEM', 'X', 'X', 'X', 'SUCCESS', now(), 'x')""".formatted(PLATFORM_TENANT),
                "audit_events row(s) belong to platform tenant");
    }

    @Test
    void abortsWhenADocumentBelongsToThePlatformTenant() throws Exception {
        assertAborts("""
                insert into documents (tenant_id, document_type_id, title, latest_version_id, owner_user_id)
                values ('%s', gen_random_uuid(), 'x', gen_random_uuid(), gen_random_uuid())""".formatted(PLATFORM_TENANT),
                "documents row(s) belong to platform tenant");
    }

    @Test
    void abortsWhenADocumentIsOwnedByTheSystemUser() throws Exception {
        assertAborts("""
                insert into documents (tenant_id, document_type_id, title, latest_version_id, owner_user_id)
                values (gen_random_uuid(), gen_random_uuid(), 'x', gen_random_uuid(), '%s')""".formatted(SYSTEM_USER),
                "documents row(s) are owned by system user");
    }

    private void assertAborts(String blockerSql, String expectedMessage) throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "36");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                // FK checks off for this session only: the row just has to exist.
                s.execute("set session_replication_role = replica");
                s.execute(blockerSql);
            }

            assertThatThrownBy(() -> migrate(url, "37")).hasStackTraceContaining(expectedMessage);

            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from tenants where tenant_id = '" + PLATFORM_TENANT + "'")).isEqualTo(1);
                assertThat(count(c, "select count(*) from users where user_id = '" + SYSTEM_USER + "'")).isEqualTo(1);
                assertThat(count(c, "select count(*) from metadata_fields where tenant_id = '" + PLATFORM_TENANT + "'"))
                        .isEqualTo(25);
                assertThat(count(c, "select count(*) from flyway_schema_history where version = '37'")).isZero();
            }
        });
    }

    /** Every single-column FK to the given table, as {table, column}. */
    private static List<String[]> foreignKeyColumns(Connection c, String referencedTable) throws SQLException {
        return strings(c, """
                select c.conrelid::regclass::text || ' ' || a.attname
                from pg_constraint c
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                where c.contype = 'f' and c.confrelid = ?::regclass and cardinality(c.conkey) = 1""", referencedTable)
                .stream().map(line -> line.split(" ")).toList();
    }

    private static UUID insertTenant(String url) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        try (Connection c = connect(url); Statement s = c.createStatement()) {
            s.execute("insert into tenants (tenant_id, tenant_name, domain, subscription_tier) values ('"
                    + tenantId + "', 'V37 IT', 'v37-it-" + tenantId + ".invalid', 'standard')");
        }
        return tenantId;
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, DB_USERNAME, DB_PASSWORD)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private interface ScratchTest {
        void run(String url) throws Exception;
    }

    private static void withScratchDatabase(ScratchTest test) throws Exception {
        String name = "dms_v37_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
