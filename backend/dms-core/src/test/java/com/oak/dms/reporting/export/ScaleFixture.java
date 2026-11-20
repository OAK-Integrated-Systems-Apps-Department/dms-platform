package com.oak.dms.reporting.export;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/**
 * A throwaway database holding one organisation with N documents, for the export tests that must run against real volume. The database
 * is created with every migration applied, filled by set-based inserts (seconds for 100,000 rows) and dropped by {@link #drop()}.
 */
final class ScaleFixture {

    static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    final UUID tenant = UUID.randomUUID();
    final String scratch = "dms_crg_export_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    final String base = DB_URL.substring(0, DB_URL.lastIndexOf('/') + 1);
    final String url = base + scratch;

    static ScaleFixture create(int documents) throws Exception {
        ScaleFixture f = new ScaleFixture();
        try (Connection admin = DriverManager.getConnection(f.base + "postgres", DB_USERNAME, DB_PASSWORD);
             Statement s = admin.createStatement()) {
            s.execute("create database " + f.scratch);
        }
        Flyway.configure().dataSource(f.url, DB_USERNAME, DB_PASSWORD).locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(f.url, DB_USERNAME, DB_PASSWORD); Statement t = c.createStatement()) {
            t.execute("set session_replication_role = replica");
            String tenant = f.tenant.toString();
            t.execute("insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                    + "', 'Export', 'export-" + tenant + "', 'standard', 'active')");
            t.execute("create temp table gen_users as select gen_random_uuid() as id, g from generate_series(1, 50) g");
            t.execute("insert into users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username) "
                    + "select id, '" + tenant + "', gen_random_uuid(), 'u' || g || '@example.com', 'User ' || g, 'User', 'N' || g, 'user' || g from gen_users");
            t.execute("create temp table gen_types as select gen_random_uuid() as id, g from generate_series(1, 10) g");
            t.execute("insert into document_types (document_type_id, tenant_id, type_code, type_name) select id, '" + tenant
                    + "', 'T' || g, 'Type ' || g from gen_types");
            t.execute("create temp table gen as select gen_random_uuid() as doc_id, gen_random_uuid() as ver_id, g "
                    + "from generate_series(1, " + documents + ") g");
            t.execute("insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, current_version_id, "
                    + "owner_user_id, created_by, created_at, access_mode, lifecycle_state) "
                    + "select o.doc_id, '" + tenant + "', ty.id, 'Document ' || lpad(o.g::text, 7, '0'), o.ver_id, o.ver_id, u.id, u.id, "
                    + "now() - (o.g % 730) * interval '1 day', 'inherit', 'active' "
                    + "from gen o join gen_types ty on ty.g = 1 + o.g % 10 join gen_users u on u.g = 1 + o.g % 50");
            t.execute("insert into document_versions (version_id, document_id, tenant_id, version_number, major_version, minor_version, "
                    + "storage_location_id, storage_object_key, original_filename, mime_type, file_hash, size_bytes, created_reason, page_count) "
                    + "select ver_id, doc_id, '" + tenant + "', 1, 1, 0, gen_random_uuid(), 'k' || g, 'file' || g || '.pdf', "
                    + "'application/pdf', 'h', 1000 + g, 'initial_upload', g % 50 from gen");
            t.execute("analyze documents");
        }
        return f;
    }

    void drop() throws Exception {
        try (Connection admin = DriverManager.getConnection(base + "postgres", DB_USERNAME, DB_PASSWORD);
             Statement s = admin.createStatement()) {
            s.execute("drop database if exists " + scratch + " with (force)");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
