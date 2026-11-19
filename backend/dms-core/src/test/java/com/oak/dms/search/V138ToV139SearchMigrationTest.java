package com.oak.dms.search;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V138-V139 (search index queue, per-tenant state, the enqueue triggers, and the permission backfill) up and down on a
 * throwaway database - same harness as {@link com.oak.dms.ownership.V123ToV125OwnershipMigrationTest}.
 *
 * <p>Every trigger is proven the same four ways: the real business write succeeds and is committed, exactly one job
 * exists afterwards, repeating the change keeps exactly one row with a higher change counter (coalescing), and a
 * rolled-back write leaves no job behind.
 */
class V138ToV139SearchMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");
    private static final String[] ROLLBACKS = {
            "/db/rollback/V139__grant_search_permission_to_document_viewers.rollback.sql",
            "/db/rollback/V138__search_index_foundation.rollback.sql"};

    // ---------------------------------------------------------------- tests

    @Test
    void up_createsQueueAndConstraintsBite() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "137");
            Ids a;
            try (Connection c = connect(url)) {
                a = seed(c);
            }
            migrate(url, "139");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "search_index_jobs")).isTrue();
                assertThat(tableExists(c, "search_tenant_state")).isTrue();
                // Existing documents get no job from the migration itself: the per-tenant backfill (S5) does that.
                assertThat(count(c, "select count(*) from search_index_jobs")).isZero();

                assertThatThrownBy(() -> exec(c, "insert into search_index_jobs (tenant_id, kind, reason, change_counter)"
                        + " values ('" + a.tenant() + "', 'whim', 'x', 1)")).hasMessageContaining("ck_search_jobs_kind");
                assertThatThrownBy(() -> exec(c, "insert into search_index_jobs (tenant_id, kind, reason, change_counter)"
                        + " values ('" + a.tenant() + "', 'document', 'x', 1)")).hasMessageContaining("ck_search_jobs_target");
                assertThatThrownBy(() -> exec(c, "insert into search_index_jobs (tenant_id, kind, document_id, reason,"
                        + " change_counter, status) values ('" + a.tenant() + "', 'document', '" + uuid() + "', 'x', 1, 'done')"))
                        .hasMessageContaining("ck_search_jobs_status");
                assertThatThrownBy(() -> exec(c, "insert into search_tenant_state (tenant_id, backfill_state) values ('"
                        + a.tenant() + "', 'half')")).hasMessageContaining("ck_search_tenant_state_backfill");
                assertThatThrownBy(() -> exec(c, "insert into search_tenant_state (tenant_id) values ('" + uuid() + "')"))
                        .hasMessageContaining("search_tenant_state");
            }
        });
    }

    @Test
    void documentsTrigger_everyWatchedColumnEnqueuesExactlyOneJob_andCoalesces() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "139");
            try (Connection c = connect(url)) {
                Ids a = seed(c);
                String doc = a.documents()[0];
                String otherUser = newUser(c, a.tenant());
                String dept = uuid();
                exec(c, "insert into departments (department_id, tenant_id, name, code) values ('" + dept + "', '"
                        + a.tenant() + "', 'Finance', 'FIN-" + dept.substring(0, 6) + "')");
                String folder = newFolder(c, a.tenant(), null);
                String otherType = uuid();
                exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name, display_order) values ('"
                        + otherType + "', '" + a.tenant() + "', 'MEMO', 'Memo', 7)");

                // (set clause, expected reason, verification query proving the business write is committed)
                String[][] cases = {
                        {"title = 'Renamed'", "title", "select count(*) from documents where document_id='" + doc + "' and title='Renamed'"},
                        {"folder_id = '" + folder + "'", "move", "select count(*) from documents where document_id='" + doc + "' and folder_id='" + folder + "'"},
                        {"owner_user_id = '" + otherUser + "'", "owner", "select count(*) from documents where document_id='" + doc + "' and owner_user_id='" + otherUser + "'"},
                        {"owner_department_id = '" + dept + "'", "owner", "select count(*) from documents where document_id='" + doc + "' and owner_department_id='" + dept + "'"},
                        {"document_type_id = '" + otherType + "'", "type", "select count(*) from documents where document_id='" + doc + "' and document_type_id='" + otherType + "'"},
                        {"lifecycle_state = 'inactive'", "lifecycle", "select count(*) from documents where document_id='" + doc + "' and lifecycle_state='inactive'"},
                        {"is_template = true", "template", "select count(*) from documents where document_id='" + doc + "' and is_template"},
                        {"is_deleted = true, deleted_at = now()", "delete", "select count(*) from documents where document_id='" + doc + "' and is_deleted"},
                        {"is_deleted = false, deleted_at = null", "restore", "select count(*) from documents where document_id='" + doc + "' and not is_deleted"},
                        {"purged_at = now()", "purge", "select count(*) from documents where document_id='" + doc + "' and purged_at is not null"},
                        {"legal_hold = true, legal_hold_at = now(), legal_hold_by = '" + a.user() + "', legal_hold_reason = 'case 42'", "hold",
                                "select count(*) from documents where document_id='" + doc + "' and legal_hold"},
                        {"updated_at = now() + interval '1 second'", "modified", "select count(*) from documents where document_id='" + doc + "' and updated_at is not null"},
                };
                for (String[] k : cases) {
                    exec(c, "delete from search_index_jobs");
                    exec(c, "update documents set " + k[0] + " where document_id = '" + doc + "'");
                    assertThat(count(c, k[2])).as("business write committed: " + k[0]).isEqualTo(1);
                    assertThat(jobs(c, doc)).as("exactly one job after " + k[0]).isEqualTo(1);
                    assertThat(reason(c, doc)).as("reason for " + k[0]).isEqualTo(k[1]);

                    // coalescing: a second change to the same document keeps one row and raises the counter
                    long before = counter(c, doc);
                    exec(c, "update documents set updated_at = now() + interval '2 second' where document_id = '" + doc + "'");
                    assertThat(jobs(c, doc)).as("still one job after repeating, " + k[0]).isEqualTo(1);
                    assertThat(counter(c, doc)).as("counter rises").isGreaterThan(before);
                }

                // A column that is not in the index does not enqueue anything.
                exec(c, "delete from search_index_jobs");
                exec(c, "update documents set checked_out_at = now(), checkout_expires_at = now() + interval '1 hour', checked_out_by = '" + a.user() + "' where document_id = '" + doc + "'");
                assertThat(jobs(c, doc)).as("unwatched column enqueues nothing").isZero();

                // Re-writing identical values is free too.
                exec(c, "update documents set title = title where document_id = '" + doc + "'");
                assertThat(jobs(c, doc)).isZero();

                // A rolled-back write leaves no job (and no change).
                c.setAutoCommit(false);
                exec(c, "update documents set title = 'Never' where document_id = '" + doc + "'");
                assertThat(jobs(c, doc)).as("visible inside the transaction").isEqualTo(1);
                c.rollback();
                c.setAutoCommit(true);
                assertThat(jobs(c, doc)).isZero();
                assertThat(count(c, "select count(*) from documents where title = 'Never'")).isZero();
            }
        });
    }

    @Test
    void insertingADocumentWithItsVersionYieldsOneJob() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "139");
            try (Connection c = connect(url)) {
                Ids a = seed(c);
                // seed() inserted two documents, each with a version (two triggers per document): one job each.
                for (String d : a.documents()) {
                    assertThat(jobs(c, d)).isEqualTo(1);
                }
                assertThat(count(c, "select count(*) from search_index_jobs")).isEqualTo(2);
            }
        });
    }

    @Test
    void metadataVersionsOcrAndRenditionTriggersEnqueueExactlyOneJobEach() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "139");
            try (Connection c = connect(url)) {
                Ids a = seed(c);
                String doc = a.documents()[0];
                String version = a.versions()[0];

                // document_metadata
                exec(c, "insert into document_type_schemas (tenant_id, document_type_id, schema_version) values ('"
                        + a.tenant() + "', '" + a.type() + "', 1)");
                exec(c, "delete from search_index_jobs");
                exec(c, "insert into document_metadata (tenant_id, document_id, document_type_id, schema_version, field_values)"
                        + " values ('" + a.tenant() + "', '" + doc + "', '" + a.type() + "', 1, '{}')");
                assertThat(count(c, "select count(*) from document_metadata where document_id='" + doc + "'")).isEqualTo(1);
                assertThat(jobs(c, doc)).isEqualTo(1);
                assertThat(reason(c, doc)).isEqualTo("metadata");
                long before = counter(c, doc);
                exec(c, "update document_metadata set field_values = '{\"f\":{\"v\":\"x\"}}' where document_id='" + doc + "'");
                assertThat(count(c, "select count(*) from document_metadata where field_values ? 'f'")).isEqualTo(1);
                assertThat(jobs(c, doc)).isEqualTo(1);
                assertThat(counter(c, doc)).isGreaterThan(before);
                exec(c, "delete from search_index_jobs");
                exec(c, "update document_metadata set updated_by = '" + a.user() + "' where document_id='" + doc + "'");
                assertThat(jobs(c, doc)).as("a metadata update with unchanged values enqueues nothing").isZero();

                // document_versions
                exec(c, "update document_versions set ocr_status = 'completed' where version_id='" + version + "'");
                assertThat(count(c, "select count(*) from document_versions where version_id='" + version + "' and ocr_status='completed'")).isEqualTo(1);
                assertThat(jobs(c, doc)).isEqualTo(1);
                assertThat(reason(c, doc)).isEqualTo("ocr");
                exec(c, "update document_versions set rendition_status = 'completed', rendition_object_key = 'r/k' where version_id='" + version + "'");
                assertThat(jobs(c, doc)).isEqualTo(1);
                assertThat(reason(c, doc)).isEqualTo("rendition");
                exec(c, "delete from search_index_jobs");
                exec(c, "update document_versions set version_comment = 'note' where version_id='" + version + "'");
                assertThat(jobs(c, doc)).as("unwatched version column").isZero();

                // ocr_outputs
                exec(c, "insert into ocr_outputs (tenant_id, version_id, extracted_text_ref, status) values ('"
                        + a.tenant() + "', '" + version + "', 'ocr/k/ocr.json', 'processing')");
                assertThat(count(c, "select count(*) from ocr_outputs where version_id='" + version + "'")).isEqualTo(1);
                assertThat(jobs(c, doc)).isEqualTo(1);
                assertThat(reason(c, doc)).isEqualTo("ocr");
                exec(c, "delete from search_index_jobs");
                exec(c, "update ocr_outputs set status = 'completed' where version_id='" + version + "'");
                assertThat(count(c, "select count(*) from ocr_outputs where status='completed'")).isEqualTo(1);
                assertThat(jobs(c, doc)).isEqualTo(1);
                exec(c, "delete from search_index_jobs");
                exec(c, "update ocr_outputs set language = 'en' where version_id='" + version + "'");
                assertThat(jobs(c, doc)).as("unwatched ocr column").isZero();

                // renditions
                exec(c, "insert into renditions (tenant_id, version_id, rendition_type, storage_object_key, status) values ('"
                        + a.tenant() + "', '" + version + "', 'searchable_pdf', 'ocr/k/searchable.pdf', 'pending')");
                assertThat(count(c, "select count(*) from renditions where version_id='" + version + "'")).isEqualTo(1);
                assertThat(jobs(c, doc)).isEqualTo(1);
                assertThat(reason(c, doc)).isEqualTo("rendition");
                long b2 = counter(c, doc);
                exec(c, "update renditions set status = 'completed' where version_id='" + version + "'");
                assertThat(count(c, "select count(*) from renditions where status='completed'")).isEqualTo(1);
                assertThat(jobs(c, doc)).isEqualTo(1);
                assertThat(counter(c, doc)).isGreaterThan(b2);

                // a rolled-back artifact write leaves nothing
                exec(c, "delete from search_index_jobs");
                c.setAutoCommit(false);
                exec(c, "update renditions set status = 'failed' where version_id='" + version + "'");
                c.rollback();
                c.setAutoCommit(true);
                assertThat(jobs(c, doc)).isZero();
            }
        });
    }

    @Test
    void foldersAndTenantsTriggersEnqueueExactlyOneJobEach() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "139");
            try (Connection c = connect(url)) {
                Ids a = seed(c);
                String parent = newFolder(c, a.tenant(), null);
                String child = newFolder(c, a.tenant(), parent);
                String other = newFolder(c, a.tenant(), null);
                exec(c, "delete from search_index_jobs");

                // moving a folder: one subtree job for it, none for its children (the indexer expands the subtree)
                exec(c, "update folders set parent_folder_id = '" + other + "' where folder_id = '" + parent + "'");
                assertThat(count(c, "select count(*) from folders where folder_id='" + parent + "' and parent_folder_id='" + other + "'")).isEqualTo(1);
                assertThat(folderJobs(c, parent)).isEqualTo(1);
                assertThat(folderJobs(c, child)).isZero();
                long before = folderCounter(c, parent);
                exec(c, "update folders set is_deleted = true, deleted_at = now() where folder_id = '" + parent + "'");
                assertThat(folderJobs(c, parent)).as("coalesced").isEqualTo(1);
                assertThat(folderCounter(c, parent)).isGreaterThan(before);
                exec(c, "delete from search_index_jobs");
                exec(c, "update folders set folder_name = 'Renamed folder' where folder_id = '" + other + "'");
                assertThat(folderJobs(c, other)).as("a folder rename changes nothing indexed").isZero();

                // removing a tenant: one purge job, coalesced on repeat
                exec(c, "delete from search_index_jobs");
                exec(c, "update tenants set is_deleted = true, deleted_at = now() where tenant_id = '" + a.tenant() + "'");
                assertThat(count(c, "select count(*) from tenants where tenant_id='" + a.tenant() + "' and is_deleted")).isEqualTo(1);
                assertThat(count(c, "select count(*) from search_index_jobs where kind='tenant_purge' and tenant_id='" + a.tenant() + "'")).isEqualTo(1);
                exec(c, "update tenants set is_deleted = false where tenant_id = '" + a.tenant() + "'");
                assertThat(count(c, "select count(*) from search_index_jobs where kind='tenant_purge'")).as("restoring does not purge").isEqualTo(1);
                exec(c, "update tenants set is_deleted = true where tenant_id = '" + a.tenant() + "'");
                assertThat(count(c, "select count(*) from search_index_jobs where kind='tenant_purge'")).as("coalesced").isEqualTo(1);
            }
        });
    }

    @Test
    void v139GrantsSearchToRolesThatCanViewDocuments_andOnlyThose() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "138");
            String viewer;
            String nonViewer;
            try (Connection c = connect(url)) {
                Ids a = seed(c);
                viewer = newRole(c, a.tenant(), "Viewer");
                nonViewer = newRole(c, a.tenant(), "Auditor");
                grant(c, a.tenant(), viewer, "docm.documents.view");
                grant(c, a.tenant(), nonViewer, "crm.view-archive");
            }
            migrate(url, "139");
            try (Connection c = connect(url)) {
                assertThat(holds(c, viewer, "dsr.search-documents")).isTrue();
                assertThat(holds(c, nonViewer, "dsr.search-documents")).isFalse();
                // idempotent: no duplicates
                assertThat(count(c, "select count(*) from role_permissions rp join permissions p on p.permission_id = rp.permission_id"
                        + " where rp.role_id = '" + viewer + "' and p.permission_code = 'dsr.search-documents'")).isEqualTo(1);
            }
        });
    }

    @Test
    void down_removesEverythingCleanly_andUpCanBeReapplied() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "137");
            try (Connection c = connect(url)) {
                seed(c);
            }
            migrate(url, "139");
            try (Connection c = connect(url); Statement s = c.createStatement()) {
                for (String script : ROLLBACKS) {
                    s.execute(read(script));
                }
                s.execute("delete from flyway_schema_history where version in ('138', '139')");
                assertThat(tableExists(c, "search_index_jobs")).isFalse();
                assertThat(tableExists(c, "search_tenant_state")).isFalse();
                assertThat(count(c, "select count(*) from pg_trigger where tgname like 'trg_search_%'")).isZero();
                assertThat(count(c, "select count(*) from pg_proc where proname like 'search_%'")).isZero();
                assertThat(count(c, "select count(*) from pg_class where relname = 'search_change_seq'")).isZero();
                // the business data is untouched, and writes still work with the triggers gone
                assertThat(count(c, "select count(*) from documents")).isEqualTo(2);
                s.execute("update documents set title = 'still fine'");
            }
            migrate(url, "139");
            try (Connection c = connect(url)) {
                assertThat(tableExists(c, "search_index_jobs")).isTrue();
                assertThat(count(c, "select count(*) from pg_trigger where tgname like 'trg_search_%'")).isEqualTo(12);
                assertThat(count(c, "select count(*) from flyway_schema_history where version in ('138','139') and success")).isEqualTo(2);
                exec(c, "update documents set title = 'after reapply' where title = 'still fine'");
                assertThat(count(c, "select count(*) from search_index_jobs")).isEqualTo(2);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static long jobs(Connection c, String doc) throws SQLException {
        return count(c, "select count(*) from search_index_jobs where kind='document' and document_id='" + doc + "'");
    }

    private static String reason(Connection c, String doc) throws SQLException {
        return text(c, "select reason from search_index_jobs where kind='document' and document_id='" + doc + "'");
    }

    private static long counter(Connection c, String doc) throws SQLException {
        return count(c, "select change_counter from search_index_jobs where kind='document' and document_id='" + doc + "'");
    }

    private static long folderJobs(Connection c, String folder) throws SQLException {
        return count(c, "select count(*) from search_index_jobs where kind='folder_subtree' and folder_id='" + folder + "'");
    }

    private static long folderCounter(Connection c, String folder) throws SQLException {
        return count(c, "select change_counter from search_index_jobs where kind='folder_subtree' and folder_id='" + folder + "'");
    }

    private static String newUser(Connection c, String tenant) throws SQLException {
        String u = uuid();
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + u + "', '" + tenant + "', '" + uuid() + "', 'u-" + u + "@test.invalid', 'u-" + u + "', 'U', 'U', 'active')");
        return u;
    }

    private static String newFolder(Connection c, String tenant, String parent) throws SQLException {
        String f = uuid();
        exec(c, "insert into folders (folder_id, tenant_id, parent_folder_id, folder_name) values ('" + f + "', '" + tenant
                + "', " + (parent == null ? "null" : "'" + parent + "'") + ", 'F-" + f.substring(0, 6) + "')");
        return f;
    }

    private static String newRole(Connection c, String tenant, String name) throws SQLException {
        String r = uuid();
        exec(c, "insert into roles (role_id, tenant_id, role_name) values ('" + r + "', '" + tenant + "', '" + name + "')");
        return r;
    }

    private static void grant(Connection c, String tenant, String role, String code) throws SQLException {
        exec(c, "insert into role_permissions (tenant_id, role_id, permission_id) select '" + tenant + "', '" + role
                + "', permission_id from permissions where permission_code = '" + code + "' and tenant_id is null");
    }

    private static boolean holds(Connection c, String role, String code) throws SQLException {
        return count(c, "select count(*) from role_permissions rp join permissions p on p.permission_id = rp.permission_id"
                + " where rp.role_id = '" + role + "' and p.permission_code = '" + code + "'") > 0;
    }

    private record Ids(String tenant, String user, String type, String[] documents, String[] versions) {
    }

    /** A tenant with a user and two documents (each with a clean version and scan result), seeded as the ownership test does. */
    private static Ids seed(Connection c) throws SQLException {
        String tenant = uuid();
        String user = uuid();
        String type = uuid();
        String location = uuid();
        String staging = uuid();
        String engine = uuid();
        exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                + "', 'V138 tenant', 'v138-" + tenant + ".invalid', 'standard', 'active')");
        exec(c, "insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name, status)"
                + " values ('" + user + "', '" + tenant + "', '" + uuid() + "', 'v138-" + user + "@test.invalid', 'v138-"
                + user + "', 'V138', 'V138', 'active')");
        exec(c, "insert into document_types (document_type_id, tenant_id, type_code, type_name) values ('" + type + "', '"
                + tenant + "', 'CONTRACT', 'Contract')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + location + "', '" + tenant + "', 'v138', 'FILE_SYSTEM', 'document', '/tmp/v138', 'active')");
        exec(c, "insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose, base_path, status)"
                + " values ('" + staging + "', '" + tenant + "', 'v138-staging', 'FILE_SYSTEM', 'staging', '/tmp/v138s', 'active')");
        exec(c, "insert into scan_engines (scan_engine_id, tenant_id, name, provider, scan_mode, timeout_seconds,"
                + " max_file_size_bytes, status) values ('" + engine + "', '" + tenant + "', 'v138', 'CLAMAV',"
                + " 'pre_store_stream', 60, 1000, 'active')");
        String[] documents = {uuid(), uuid()};
        String[] versions = {uuid(), uuid()};
        c.setAutoCommit(false);
        for (int i = 0; i < documents.length; i++) {
            String document = documents[i];
            String version = versions[i];
            exec(c, "insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id)"
                    + " values ('" + document + "', '" + tenant + "', '" + type + "', 'Doc', '" + version + "', '" + user + "')");
            exec(c, "insert into document_versions (version_id, document_id, tenant_id, version_number, major_version,"
                    + " minor_version, storage_location_id, storage_object_key, original_filename, mime_type, file_hash,"
                    + " size_bytes, created_reason, scan_status) values ('" + version + "', '" + document + "', '" + tenant
                    + "', 1, 1, 0, '" + location + "', 'k" + version + "', 'a.pdf', 'application/pdf', 'h', 1, 'initial_upload', 'clean')");
            exec(c, "insert into scan_results (tenant_id, scan_engine_id, document_version_id, staging_location_id,"
                    + " original_filename, file_hash, size_bytes, mime_type, status, engine_name, scan_mode, completed_at,"
                    + " uploaded_by, ingestion_kind, created_reason, document_type_id, title) values ('" + tenant + "', '"
                    + engine + "', '" + version + "', '" + staging + "', 'a.pdf', 'h', 1, 'application/pdf', 'clean', 'v138',"
                    + " 'pre_store_stream', now(), '" + user + "', 'new_document', 'initial_upload', '" + type + "', 'Doc')");
        }
        c.commit();
        c.setAutoCommit(true);
        return new Ids(tenant, user, type, documents, versions);
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        return count(c, "select count(*) from information_schema.tables where table_schema = 'public' and table_name = '"
                + table + "'") == 1;
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
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
        try (InputStream in = V138ToV139SearchMigrationTest.class.getResourceAsStream(script)) {
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
