package com.oak.dms.importexport;

import com.oak.dms.encryption.EncryptionJobStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
 * V47 (one bulk import implementation: migration_* dropped, upload_sessions
 * linked from import_items) and V48 (export_jobs extended for generating
 * exports) on a scratch database - same harness as V42BulkImportMigrationTest.
 */
class V47V48ImportExportMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    @Test
    void v47_dropsTheDuplicateImportTables_keepsUploadSessions_andLinksThem() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "46");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "migration_batches")).isTrue();
            }
            migrate(url, "47");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "migration_batches")).isFalse();
                assertThat(tableExists(c, "migration_items")).isFalse();
                assertThat(tableExists(c, "upload_sessions")).isTrue();
                assertThat(strings(c, "select conname from pg_constraint where conrelid = 'import_items'::regclass"))
                        .contains("fk_import_items_upload_session");
            }
        });
    }

    @Test
    void v48_addsExportColumns_withDefaults_andChecksTheirValues() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "48");
            try (Connection c = connect(url)) {
                assertThat(strings(c, "select column_name from information_schema.columns where table_name = 'export_jobs'"))
                        .contains("metadata_format", "request_params", "document_count", "file_size_bytes",
                                "failure_reason", "started_at", "completed_at", "storage_location_id");

                String tenant = UUID.randomUUID().toString();
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                        + tenant + "', 'V48', 'v48-" + tenant + "', 'standard', 'active')");
                String user = UUID.randomUUID().toString();
                exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status) "
                        + "values ('" + user + "', '" + tenant + "', '" + UUID.randomUUID() + "', 'v48-" + user
                        + "@t.invalid', 'v48-" + user + "', 'V48', 'V48', 'active')");
                // A V41-shaped insert still works: every new column has a default.
                exec(c, "insert into export_jobs (tenant_id, requested_by, export_type) values ('" + tenant + "', '"
                        + user + "', 'bulk_selection')");
                assertThat(strings(c, "select metadata_format || '/' || request_params::text || '/' || document_count "
                        + "from export_jobs")).containsExactly("xlsx/{}/0");

                assertThatThrownBy(() -> exec(c, "insert into export_jobs (tenant_id, requested_by, export_type, "
                        + "metadata_format) values ('" + tenant + "', '" + user + "', 'bulk_selection', 'pdf')"))
                        .hasMessageContaining("ck_export_jobs_metadata_format");
                assertThatThrownBy(() -> exec(c, "insert into export_jobs (tenant_id, requested_by, export_type) "
                        + "values ('" + tenant + "', '" + user + "', 'everything')"))
                        .hasMessageContaining("ck_export_jobs_export_type");
            }
        });
    }

    @Test
    void v49_v50_onlyUsedFieCodesStayActive_andTemplateNamesAreUniquePerTypeAmongLiveOnes() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "50");
            try (Connection c = connect(url)) {
                assertThat(strings(c, "select permission_code from permissions where permission_code like 'fie.%' "
                        + "and tenant_id is null and not is_deprecated")).containsExactlyInAnyOrder(
                        "fie.bulk-import-documents.upload-file", "fie.bulk-export-documents.execute-export",
                        "fie.manage-import-templates.create-template", "fie.manage-import-templates.update-template",
                        "fie.manage-import-templates.delete-template");

                String tenant = UUID.randomUUID().toString();
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                        + tenant + "', 'V50', 'v50-" + tenant + "', 'standard', 'active')");
                String type = UUID.randomUUID().toString();
                exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('"
                        + type + "', '" + tenant + "', 'V50', 'V50 type')");
                String insert = "insert into import_templates (tenant_id, document_type_id, name, columns) values ('"
                        + tenant + "', '" + type + "', ";
                exec(c, insert + "'Finance', '[{\"fieldKey\": \"amount\", \"header\": \"Total\"}]')");
                assertThatThrownBy(() -> exec(c, insert + "'FINANCE', '[]')"))
                        .hasMessageContaining("uq_import_templates_live_name");
                exec(c, "update import_templates set is_deleted = true");
                exec(c, insert + "'Finance', '[]')"); // the name is free again once the old one is deleted
                assertThatThrownBy(() -> exec(c, insert + "'Bad', '{}')"))
                        .hasMessageContaining("ck_import_templates_columns_array");
                assertThat(strings(c, "select column_name from information_schema.columns where table_name = "
                        + "'import_batches' and column_name = 'import_template_id'")).hasSize(1);
            }
        });
    }

    @Test
    void v51_folderNamesAreUniqueAmongLiveSiblings_andTreesStayInsideTheirTenant() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "51");
            try (Connection c = connect(url)) {
                String tenant = UUID.randomUUID().toString();
                String other = UUID.randomUUID().toString();
                for (String t : new String[]{tenant, other}) {
                    exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                            + t + "', 'V51', 'v51-" + t + "', 'standard', 'active')");
                }
                String root = UUID.randomUUID().toString();
                exec(c, "insert into folders (folder_id, tenant_id, folder_name) values ('" + root + "', '" + tenant + "', 'Finance')");
                assertThatThrownBy(() -> exec(c, "insert into folders (tenant_id, folder_name) values ('" + tenant + "', 'FINANCE')"))
                        .hasMessageContaining("uq_folders_live_sibling_name");
                exec(c, "insert into folders (tenant_id, parent_folder_id, folder_name) values ('" + tenant + "', '" + root
                        + "', 'Finance')"); // same name, different parent
                exec(c, "insert into folders (tenant_id, folder_name) values ('" + other + "', 'Finance')"); // other tenant
                assertThatThrownBy(() -> exec(c, "insert into folders (tenant_id, parent_folder_id, folder_name) values ('"
                        + other + "', '" + root + "', 'Leak')"))
                        .hasMessageContaining("fk_folders_parent");
                assertThat(strings(c, "select column_name from information_schema.columns where column_name = 'folder_id' "
                        + "and table_name in ('documents', 'scan_results', 'import_batches')")).hasSize(1);
                assertThat(strings(c, "select permission_code from permissions where permission_code = 'docm.documents.move'"))
                        .hasSize(1);
            }
        });
    }

    @Test
    void v53_foldersInheritByDefault_andAccessEntriesStayInsideTheirTenant() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "53");
            try (Connection c = connect(url)) {
                String tenant = UUID.randomUUID().toString();
                String other = UUID.randomUUID().toString();
                for (String t : new String[]{tenant, other}) {
                    exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                            + t + "', 'V53', 'v53-" + t + "', 'standard', 'active')");
                }
                String hr = UUID.randomUUID().toString();
                exec(c, "insert into folders (folder_id, tenant_id, folder_name) values ('" + hr + "', '" + tenant + "', 'HR')");
                assertThat(strings(c, "select access_mode from folders where folder_id = '" + hr + "'"))
                        .containsExactly("inherit");
                assertThatThrownBy(() -> exec(c, "update folders set access_mode = 'secret' where folder_id = '" + hr + "'"))
                        .hasMessageContaining("ck_folders_access_mode");

                String person = UUID.randomUUID().toString();
                exec(c, "insert into folder_access (tenant_id, folder_id, principal_type, principal_id, access_level) values ('"
                        + tenant + "', '" + hr + "', 'user', '" + person + "', 'view')");
                assertThatThrownBy(() -> exec(c, "insert into folder_access (tenant_id, folder_id, principal_type, "
                        + "principal_id, access_level) values ('" + tenant + "', '" + hr + "', 'user', '" + person + "', 'edit')"))
                        .hasMessageContaining("uq_folder_access_principal");
                assertThatThrownBy(() -> exec(c, "insert into folder_access (tenant_id, folder_id, principal_type, "
                        + "principal_id, access_level) values ('" + tenant + "', '" + hr + "', 'group', '"
                        + UUID.randomUUID() + "', 'view')"))
                        .hasMessageContaining("ck_folder_access_principal_type");
                // An entry can't point at another tenant's folder.
                assertThatThrownBy(() -> exec(c, "insert into folder_access (tenant_id, folder_id, principal_type, "
                        + "principal_id, access_level) values ('" + other + "', '" + hr + "', 'user', '" + UUID.randomUUID()
                        + "', 'view')"))
                        .hasMessageContaining("fk_folder_access_folder");
            }
        });
    }

    @Test
    void v54_documentsFollowTheirFolderByDefault_andCantBeOpenedToEveryoneOnTheirOwn() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "54");
            try (Connection c = connect(url)) {
                assertThat(strings(c, "select column_default from information_schema.columns "
                        + "where table_name = 'documents' and column_name = 'access_mode'"))
                        .anySatisfy(d -> assertThat(d).contains("inherit"));
                assertThat(tableExists(c, "document_access")).isTrue();
                assertThat(strings(c, "select conname from pg_constraint where conrelid = 'documents'::regclass "
                        + "and conname = 'ck_documents_access_mode'")).hasSize(1);
                // Only inherit and restricted - "everyone" is a folder setting, not a document one.
                assertThat(strings(c, "select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conname = 'ck_documents_access_mode'"))
                        .allSatisfy(def -> assertThat(def).doesNotContain("everyone"));
                assertThat(strings(c, "select conname from pg_constraint where conrelid = 'document_access'::regclass"))
                        .contains("fk_document_access_document", "uq_document_access_principal",
                                "ck_document_access_level");
            }
        });
    }

    @Test
    void v55_oneKeyPerStoredObject_withAKnownProviderAndAPositiveVersion() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "57");
            try (Connection c = connect(url)) {
                String location = UUID.randomUUID().toString();
                exec(c, "insert into storage_locations (storage_location_id, name, provider, purpose) values ('"
                        + location + "', 'V55', 'FILE_SYSTEM', 'document')");
                String insert = "insert into storage_object_keys (storage_location_id, object_key, wrapped_key, "
                        + "key_provider, key_version, algorithm, plaintext_size) values ('" + location + "', ";
                exec(c, insert + "'a.pdf', 'vault:v1:x', 'openbao', 1, 'AES256_GCM_HKDF_STREAMING', 10)");
                assertThatThrownBy(() -> exec(c, insert + "'a.pdf', 'vault:v1:y', 'openbao', 1, 'A', 10)"))
                        .hasMessageContaining("uq_storage_object_keys_object");
                assertThatThrownBy(() -> exec(c, insert + "'b.pdf', 'x:v1:y', 'plaintext', 1, 'A', 10)"))
                        .hasMessageContaining("ck_storage_object_keys_provider");
                assertThatThrownBy(() -> exec(c, insert + "'c.pdf', 'vault:v0:y', 'openbao', 0, 'A', 10)"))
                        .hasMessageContaining("ck_storage_object_keys_version");
                // V56: an encrypted copy beside the original can't be the original's own key.
                assertThatThrownBy(() -> exec(c, "update storage_object_keys set physical_object_key = object_key"))
                        .hasMessageContaining("ck_storage_object_keys_physical");
                // V57: every key names its master-key scope (platform by default) and can name its tenant.
                assertThat(strings(c, "select key_scope from storage_object_keys")).containsExactly("platform");
                assertThatThrownBy(() -> exec(c, "update storage_object_keys set tenant_id = '"
                        + UUID.randomUUID() + "'"))
                        .hasMessageContaining("foreign key");
            }
        });
    }

    @Test
    void v58_anEncryptionJobRunsOncePerTenant_untilItFinishesOrItsHeartbeatStops() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "58");
            String tenant = UUID.randomUUID().toString();
            try (Connection c = connect(url)) {
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('"
                        + tenant + "', 'V58', 'v58-" + tenant + "', 'standard', 'active')");
            }
            UUID tenantId = UUID.fromString(tenant);
            EncryptionJobStore jobs = new EncryptionJobStore(
                    new JdbcTemplate(new DriverManagerDataSource(url, DB_USERNAME, DB_PASSWORD)));

            assertThat(jobs.claim(tenantId, "rewrap", null)).isTrue();
            assertThat(jobs.claim(tenantId, "rewrap", null)).isFalse(); // a second click or instance
            assertThat(jobs.claim(tenantId, "encrypt-existing", null)).isTrue(); // another job is separate
            jobs.progress(tenantId, "rewrap", 5, 1, "one key failed");
            assertThat(jobs.get(tenantId, "rewrap").running()).isTrue();
            assertThat(jobs.get(tenantId, "rewrap").processed()).isEqualTo(5);

            var finished = jobs.finish(tenantId, "rewrap", false, null);
            assertThat(finished.running()).isFalse();
            assertThat(finished.lastError()).isEqualTo("one key failed");
            assertThat(jobs.claim(tenantId, "rewrap", null)).isTrue(); // finished: can run again, from zero
            assertThat(jobs.get(tenantId, "rewrap").processed()).isZero();

            // Its instance died: the heartbeat stops, the job shows as interrupted and can be claimed again.
            try (Connection c = connect(url)) {
                exec(c, "update encryption_jobs set heartbeat_at = now() - interval '10 minutes' "
                        + "where job = 'rewrap'");
                assertThat(jobs.get(tenantId, "rewrap").running()).isFalse();
                assertThat(jobs.get(tenantId, "rewrap").lastError()).contains("Interrupted");
                assertThat(jobs.claim(tenantId, "rewrap", null)).isTrue();
                assertThatThrownBy(() -> exec(c, "insert into encryption_jobs (tenant_id, job, status, started_at, "
                        + "heartbeat_at) values ('" + tenant + "', 'shred', 'running', now(), now())"))
                        .hasMessageContaining("ck_encryption_jobs_job");
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        return !strings(c, "select table_name from information_schema.tables where table_schema = 'public' "
                + "and table_name = '" + table + "'").isEmpty();
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

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, DB_USERNAME, DB_PASSWORD).locations("classpath:db/migration")
                .target(target).load().migrate();
    }

    @FunctionalInterface
    private interface ScratchTest {
        void run(String url) throws Exception;
    }

    private static void withScratchDatabase(ScratchTest test) throws Exception {
        String name = "dms_v48_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
