package com.oak.dms.metadata;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V36's backfill from {@code document_type_fields} into
 * {@code document_type_schemas} on real V30 seed data plus a publish
 * history, and V36's guard on a non-empty {@code document_metadata_values}.
 * Each test migrates its own throwaway database (created on the same server
 * the app's {@code DB_URL} points at, and dropped afterwards) step by step
 * with Flyway directly, so it never touches the shared test database.
 */
class V36MigrationIT {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    @Test
    void backfillsEveryLiveVersionPreservingMappingIdsOrderAndHistory() throws Exception {
        withScratchDatabase(url -> {
            // Stop before V30: it seeds only tenants that exist when it runs.
            migrate(url, "29");
            UUID tenantId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                s.execute("insert into tenants (tenant_id, tenant_name, domain, subscription_tier) values ('"
                        + tenantId + "', 'V36 IT', 'v36-it-" + tenantId + ".invalid', 'standard')");
                s.execute("insert into users (user_id, tenant_id, keycloak_id, email, display_name, first_name, username) "
                        + "values ('" + userId + "', '" + tenantId + "', gen_random_uuid(), 'v36@it.invalid', 'V36', 'V36', 'v36')");
            }
            // V30 gives this tenant the real starter catalogue and
            // schema_version 1 mappings; V31-V34 then run over them as usual.
            migrate(url, "34");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                s.execute("update document_type_fields set created_by = '" + userId + "' where tenant_id = '" + tenantId + "'");

                // Publish history on CONTRACT: v1 archived, v2 active with
                // 'tags' newly mandatory and one soft-deleted row.
                s.execute("""
                        update document_type_fields dtf set status = 'archived', archived_at = '2026-01-02T03:04:05Z'
                        from document_types dt
                        where dt.document_type_id = dtf.document_type_id and dt.type_code = 'CONTRACT'
                          and dt.tenant_id = '%s'""".formatted(tenantId));
                s.execute("""
                        insert into document_type_fields (tenant_id, document_type_id, field_id, schema_version, status,
                                is_mandatory, is_hidden, requires_backfill, display_order, field_group,
                                default_value_override, created_by, created_at, is_deleted)
                        select dtf.tenant_id, dtf.document_type_id, dtf.field_id, 2, 'active',
                               dtf.is_mandatory or mf.field_key = 'tags', mf.field_key = 'description',
                               mf.field_key = 'tags', dtf.display_order, dtf.field_group,
                               case when mf.field_key = 'author' then 'x' end, '%s', '2026-01-02T03:04:05Z',
                               mf.field_key = 'reference_number'
                        from document_type_fields dtf
                        join document_types dt on dt.document_type_id = dtf.document_type_id
                        join metadata_fields mf on mf.field_id = dtf.field_id
                        where dt.type_code = 'CONTRACT' and dt.tenant_id = '%s'""".formatted(userId, tenantId));
                s.execute("update document_types set current_schema_version = 2 where type_code = 'CONTRACT' and tenant_id = '"
                        + tenantId + "'");
                // Never-published types, at version 1 and at a bumped version.
                s.execute("insert into document_types (tenant_id, type_code, type_name, display_order, current_schema_version) values "
                        + "('" + tenantId + "', 'NEVER', 'Never published', 100, 1), "
                        + "('" + tenantId + "', 'BUMPED', 'Bumped, no rows', 110, 3)");
            }

            List<String> expectedContractV1Ids;
            try (Connection c = connect(url)) {
                expectedContractV1Ids = strings(c, """
                        select dtf.document_type_field_id::text from document_type_fields dtf
                        join document_types dt on dt.document_type_id = dtf.document_type_id
                        where dt.type_code = 'CONTRACT' and dt.tenant_id = ? and dtf.schema_version = 1
                        order by dtf.display_order""", tenantId);
            }
            assertThat(expectedContractV1Ids).hasSize(13);

            migrate(url, "36");

            try (Connection c = connect(url)) {
                // Every live legacy row is in exactly one schema row, with identical values.
                assertThat(count(c, """
                        select count(*) from document_type_fields d where not d.is_deleted and 1 <> (
                          select count(*) from document_type_schemas s, jsonb_array_elements(s.fields) e
                          where s.document_type_id = d.document_type_id and s.schema_version = d.schema_version
                            and s.tenant_id = d.tenant_id
                            and (e->>'mappingId')::uuid = d.document_type_field_id
                            and (e->>'fieldId')::uuid = d.field_id
                            and (e->>'mandatory')::boolean = d.is_mandatory
                            and (e->>'hidden')::boolean = d.is_hidden
                            and (e->>'requiresBackfill')::boolean = d.requires_backfill
                            and (e->>'displayOrder')::int = d.display_order
                            and (e->>'fieldGroup') is not distinct from d.field_group
                            and (e->>'defaultValueOverride') is not distinct from d.default_value_override
                            and s.status = d.status
                            and s.archived_at is not distinct from d.archived_at)""")).isZero();
                // ...and nothing else: no soft-deleted rows, no extra elements.
                assertThat(count(c, """
                        select count(*) from document_type_schemas s where jsonb_array_length(s.fields) <> (
                          select count(*) from document_type_fields d
                          where d.document_type_id = s.document_type_id and d.schema_version = s.schema_version
                            and not d.is_deleted)""")).isZero();
                // Elements are stored in displayOrder order.
                assertThat(count(c, """
                        select count(*) from document_type_schemas s where
                          (select array_agg((e->>'displayOrder')::int order by o) from jsonb_array_elements(s.fields) with ordinality x(e, o))
                          is distinct from
                          (select array_agg((e->>'displayOrder')::int order by (e->>'displayOrder')::int) from jsonb_array_elements(s.fields) e)""")).isZero();
                // Every document type has a row at its current version.
                assertThat(count(c, """
                        select count(*) from document_types dt where not exists (
                          select 1 from document_type_schemas s
                          where s.document_type_id = dt.document_type_id and s.schema_version = dt.current_schema_version)""")).isZero();

                assertThat(strings(c, """
                        select dt.type_code || ' v' || s.schema_version || ' ' || s.status || ' ' || jsonb_array_length(s.fields)
                        from document_type_schemas s join document_types dt on dt.document_type_id = s.document_type_id
                        where s.tenant_id = ? order by dt.type_code, s.schema_version""", tenantId))
                        .containsExactly(
                                "BUMPED v3 active 0",
                                "CONTRACT v1 archived 13",
                                "CONTRACT v2 active 12",
                                "GENERAL v1 active 11",
                                "INVOICE v1 active 11",
                                "NEVER v1 active 0",
                                "POLICY v1 active 12");

                assertThat(strings(c, """
                        select e->>'mappingId' from document_type_schemas s
                        join document_types dt on dt.document_type_id = s.document_type_id,
                        jsonb_array_elements(s.fields) with ordinality x(e, o)
                        where dt.type_code = 'CONTRACT' and s.tenant_id = ? and s.schema_version = 1 order by o""", tenantId))
                        .containsExactlyElementsOf(expectedContractV1Ids);

                assertThat(strings(c, """
                        select s.archived_at = '2026-01-02T03:04:05Z' and s.published_by::text = ?
                               and s.published_at = (select min(created_at) from document_type_fields d
                                                     where d.document_type_id = s.document_type_id and d.schema_version = 1)
                        from document_type_schemas s join document_types dt on dt.document_type_id = s.document_type_id
                        where dt.type_code = 'CONTRACT' and s.tenant_id = ? and s.schema_version = 1""",
                        userId.toString(), tenantId)).containsExactly("t");
                assertThat(strings(c, """
                        select (e->>'mandatory') || ',' || (e->>'requiresBackfill')
                        from document_type_schemas s join document_types dt on dt.document_type_id = s.document_type_id,
                        jsonb_array_elements(s.fields) e join metadata_fields mf on mf.field_id = (e->>'fieldId')::uuid
                        where dt.type_code = 'CONTRACT' and s.tenant_id = ? and s.schema_version = 2 and mf.field_key = 'tags'""",
                        tenantId)).containsExactly("true,true");

                assertThat(count(c, "select count(*) from pg_class where relname = 'document_metadata_values'")).isZero();
                assertThat(count(c, "select count(*) from pg_class where relname = 'document_metadata'")).isEqualTo(1);
                assertThatThrownBy(() -> {
                    try (Statement s = c.createStatement()) {
                        s.execute("delete from document_type_fields where false");
                    }
                }).hasMessageContaining("document_type_fields is read-only since V36");
            }
        });
    }

    @Test
    void refusesToDropANonEmptyDocumentMetadataValuesTable() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "34");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                // FK checks off for this session only: the row just has to exist.
                s.execute("set session_replication_role = replica");
                s.execute("insert into document_metadata_values (tenant_id, document_id, field_id, value_text) "
                        + "values (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'x')");
            }

            assertThatThrownBy(() -> migrate(url, "36"))
                    .hasStackTraceContaining("document_metadata_values is not empty");
            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from document_metadata_values")).isEqualTo(1);
                assertThat(count(c, "select count(*) from pg_class where relname = 'document_type_schemas'")).isZero();
            }
        });
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
        String name = "dms_v36_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> strings(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
