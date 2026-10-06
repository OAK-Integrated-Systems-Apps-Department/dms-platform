package com.oak.dms.watermark;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.watermark.dto.WatermarkRequest;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * A tenant, a user, storage in this test's own {@code @TempDir}, and a
 * document whose version is scanned clean - everything a watermark test
 * needs, without a scanner.
 *
 * <h2>Why rows are inserted rather than uploaded</h2>
 * The real ingestion path needs a live ClamAV, which is why
 * {@code DocumentUploadIT} cannot run outside CI. Inserting the rows
 * directly skips the scanner while still exercising the real storage
 * adapter, the real encryption layer, the real {@code VersionContent} scan
 * gate and the real access checks - nothing about the watermark path is
 * mocked.
 *
 * <h2>What the schema forces, and is worth knowing</h2>
 * <ul>
 *   <li>The scan gate is a DATABASE TRIGGER
 *       ({@code document_versions_require_clean_scan}), not only application
 *       code: no version row may exist without a clean {@code scan_results}
 *       row carrying the same file hash. There is no inserting around it,
 *       which is the property that makes the gate worth having - so a seeded
 *       document needs a seeded scan result.</li>
 *   <li>{@code documents} and {@code document_versions} reference each
 *       other, and {@code documents.latest_version_id} is NOT NULL, so
 *       neither can be written first. The schema solves it with
 *       {@code DEFERRABLE INITIALLY DEFERRED} keys - which means both
 *       inserts must share ONE transaction, or each commit is checked alone
 *       and fails.</li>
 * </ul>
 */
abstract class WatermarkFixtures {

    protected static final UUID SYSTEM_CONFIDENTIAL = UUID.fromString("d0cc0000-0000-4000-8000-000000000001");
    protected static final String PDF_MIME = "application/pdf";

    @Autowired
    protected TenantRepository tenantRepository;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected DocumentRepository documentRepository;
    @Autowired
    protected DocumentVersionRepository versionRepository;
    @Autowired
    protected DocumentTypeRepository documentTypeRepository;
    @Autowired
    protected StorageLocationRepository storageLocationRepository;
    @Autowired
    protected ObjectStorageServiceFactory storage;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    protected TransactionTemplate transactions;

    @TempDir
    protected Path tempDir;

    @BeforeEach
    void prepareFixtures() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @Autowired
    protected com.oak.dms.security.rbac.repository.RoleRepository roleRepository;
    @Autowired
    protected com.oak.dms.security.rbac.repository.RolePermissionRepository rolePermissionRepository;
    @Autowired
    protected com.oak.dms.security.rbac.repository.UserRoleRepository userRoleRepository;

    /**
     * Gives this fixture's user one permission, through a real role.
     * <p>
     * Needed by any test that goes through HTTP: the endpoints are
     * @PreAuthorize-guarded, and the fixture user is an ordinary user with
     * no roles, so without this every request is a 403 before the feature
     * is reached. Service-level tests do not need it - document ownership
     * is enough there - which is why the two look inconsistent.
     * <p>
     * The role is tenant-scoped, so the fixture cleanup removes it with
     * everything else.
     */
    protected void grant(Fixture fixture, String permissionCode) {
        UUID permissionId = jdbc.queryForObject(
                "select permission_id from permissions where permission_code = ?", UUID.class, permissionCode);
        com.oak.dms.security.rbac.entity.Role role = roleRepository.save(
                com.oak.dms.security.rbac.entity.Role.builder()
                        .tenantId(fixture.tenantId())
                        .roleName("wm-test-" + UUID.randomUUID())
                        .description("Watermark test role")
                        .status(com.oak.dms.security.rbac.entity.Role.RoleStatus.active)
                        .build());
        rolePermissionRepository.save(com.oak.dms.security.rbac.entity.RolePermission.builder()
                .tenantId(fixture.tenantId()).roleId(role.getRoleId()).permissionId(permissionId).build());
        userRoleRepository.save(com.oak.dms.security.rbac.entity.UserRole.builder()
                .tenantId(fixture.tenantId()).userId(fixture.userId()).roleId(role.getRoleId()).build());
    }

    /** Every tenant this test made, so it can take them away again. */
    private final List<UUID> createdTenants = new ArrayList<>();

    /**
     * Registers a tenant for cleanup. Tests that build their own tenants
     * rather than using {@link #fixture()} call this, so every watermark
     * test leaves the database as it found it regardless of how it got
     * there.
     */
    protected UUID trackTenant(UUID tenantId) {
        createdTenants.add(tenantId);
        return tenantId;
    }

    /**
     * Removes everything the test created.
     * <p>
     * The suite shares one database, and several ITs assert on tables that
     * are global rather than tenant-scoped - {@code TenantAdministrationIT}
     * pages through ALL tenants, for instance. A watermark test that leaves
     * a few dozen tenants behind doesn't fail itself; it fails somebody
     * else's test, intermittently, depending on execution order. That is a
     * miserable thing to debug, so these tests clean up after themselves
     * rather than relying on CI starting from an empty database.
     * <p>
     * Deleted in dependency order with SQL rather than repositories: the
     * rows cross several modules' entities, and this is bookkeeping, not
     * behaviour under test.
     */
    @AfterEach
    void removeWhatThisTestCreated() {
        TenantContext.clear();
        for (UUID tenantId : createdTenants) {
            waitForJobsToSettle(tenantId);
            deleteEverythingOf(tenantId);
        }
        createdTenants.clear();
    }

    /**
     * Up to three attempts, because a job that finished between the settle
     * check and the delete can still write one last audit row. Each attempt
     * is a whole transaction, so a partial delete is never left behind.
     */
    private void deleteEverythingOf(UUID tenantId) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // One transaction for the lot. documents and
                // document_versions point at each other, and
                // documents.latest_version_id is NOT NULL - so neither can be
                // emptied first, and nulling the pointers isn't allowed
                // either. The DEFERRABLE INITIALLY DEFERRED keys make both
                // deletes legal as long as they commit together.
                transactions.executeWithoutResult(status -> {
                    for (String statement : new String[]{
                            "delete from watermark_jobs where tenant_id = ?",
                            "delete from watermark_templates where tenant_id = ?",
                            "delete from audit_events where tenant_id = ?",
                            "delete from document_metadata where document_id in "
                                    + "(select document_id from documents where tenant_id = ?)",
                            "delete from document_access where document_id in "
                                    + "(select document_id from documents where tenant_id = ?)",
                            "delete from scan_results where tenant_id = ?",
                            "delete from document_versions where tenant_id = ?",
                            "delete from documents where tenant_id = ?",
                            "delete from document_types where tenant_id = ?",
                            "delete from user_roles where tenant_id = ?",
                            "delete from role_permissions where tenant_id = ?",
                            "delete from roles where tenant_id = ?",
                            "delete from storage_object_keys where tenant_id = ?",
                            "delete from storage_locations where tenant_id = ?",
                            "delete from users where tenant_id = ?",
                            "delete from tenants where tenant_id = ?"}) {
                        jdbc.update(statement, tenantId);
                    }
                });
                return;
            } catch (RuntimeException e) {
                if (attempt == 3) {
                    // A cleanup failure must not turn a passing test red, or
                    // mask the real failure of a failing one. It is reported
                    // so a growing database doesn't stay mysterious.
                    LoggerFactory.getLogger(WatermarkFixtures.class)
                            .warn("Could not clean up watermark test tenant {}: {}", tenantId, e.toString());
                    return;
                }
                sleepBriefly();
            }
        }
    }

    /**
     * Waits for the tenant's jobs to stop running before anything is
     * deleted.
     * <p>
     * The worker is asynchronous, so a test can return while its job is
     * still finishing. The cleanup would then delete {@code audit_events}
     * and {@code storage_object_keys}, the worker would write its
     * completion audit and its staged output a moment later, and the final
     * {@code delete from tenants} would fail on a foreign key to a row that
     * did not exist when the delete started - leaving the whole tenant
     * behind. That showed up as exactly two leftover tenants per full run,
     * from whichever tests happened to lose the race.
     * <p>
     * Deliberately silent on timeout: a test that means to leave a job
     * running forever is a legitimate test (see
     * {@code WatermarkWorkerFailureIT}), and cleanup is not the place to
     * fail it. The retry around the deletes covers what is left.
     */
    private void waitForJobsToSettle(UUID tenantId) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            Integer running = jdbc.queryForObject(
                    "select count(*) from watermark_jobs where tenant_id = ? and status in ('pending', 'processing')",
                    Integer.class, tenantId);
            if (running == null || running == 0) {
                return;
            }
            sleepBriefly();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected record Fixture(UUID tenantId, UUID userId, String username, UUID documentId, UUID versionId,
                             long sizeBytes) {
    }

    protected static WatermarkRequest customText(String text) {
        return new WatermarkRequest(null, text, null, null, null, null, null, null, null);
    }

    protected Fixture fixture() {
        return fixture("report.pdf", PDF_MIME);
    }

    protected Fixture fixture(String filename, String mimeType) {
        return fixture(filename, mimeType, -1);
    }

    /** Same fixture with content of the caller's choosing - a real PDF or image. */
    protected Fixture fixtureWithContent(String filename, String mimeType, byte[] content) {
        return fixture(filename, mimeType, -1, content);
    }

    protected Fixture fixture(String filename, String mimeType, long declaredSize) {
        return fixture(filename, mimeType, declaredSize, minimalPdf());
    }

    private Fixture fixture(String filename, String mimeType, long declaredSize, byte[] content) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Watermark test tenant " + UUID.randomUUID())
                .domain("watermark-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        createdTenants.add(tenant.getTenantId());

        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@watermark-test.invalid")
                .username("user-" + keycloakId.toString().substring(0, 8))
                .firstName("Test").lastName("Requester").displayName("Test Requester")
                .status(User.UserStatus.active)
                .build());

        StorageLocation location = storageLocationRepository.save(StorageLocation.builder()
                .tenantId(tenant.getTenantId())
                .name("wm-doc-" + UUID.randomUUID())
                .provider(StorageProvider.FILE_SYSTEM)
                .purpose(StoragePurpose.document)
                .basePath(tempDir.resolve(tenant.getTenantId().toString()).toString())
                .status(StorageLocation.Status.active)
                .build());
        // Referenced by the seeded scan result; never read.
        UUID stagingLocation = storageLocationRepository.save(StorageLocation.builder()
                .tenantId(tenant.getTenantId())
                .name("wm-staging-" + UUID.randomUUID())
                .provider(StorageProvider.FILE_SYSTEM)
                .purpose(StoragePurpose.staging)
                .basePath(tempDir.resolve(tenant.getTenantId() + "-staging").toString())
                .status(StorageLocation.Status.active)
                .build()).getStorageLocationId();

        TenantContext.clear();
        TenantContext.set(tenant.getTenantId(), user.getUserId());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        DocumentType type = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenant.getTenantId())
                .typeCode("WM_" + suffix)
                .typeName("Watermark test type " + suffix)
                .build());

        long size = declaredSize >= 0 ? declaredSize : content.length;
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String objectKey = tenant.getTenantId() + "/" + documentId + "/" + versionId;
        storage.resolve(location).upload(location, objectKey, new ByteArrayInputStream(content),
                content.length, mimeType);

        transactions.executeWithoutResult(status -> {
            seedCleanScanResult(tenant.getTenantId(), user.getUserId(), type.getDocumentTypeId(),
                    versionId, filename, mimeType, sha256(content), stagingLocation);
            versionRepository.save(DocumentVersion.builder()
                    .versionId(versionId)
                    .documentId(documentId)
                    .tenantId(tenant.getTenantId())
                    .versionNumber(1)
                    .majorVersion(1)
                    .minorVersion(0)
                    .storageLocationId(location.getStorageLocationId())
                    .storageObjectKey(objectKey)
                    .originalFilename(filename)
                    .mimeType(mimeType)
                    .sizeBytes(size)
                    .fileHash(sha256(content))
                    .createdReason("initial_upload")
                    .scanStatus(DocumentVersion.SCAN_STATUS_CLEAN)
                    .createdBy(user.getUserId())
                    .build());
            documentRepository.save(Document.builder()
                    .documentId(documentId)
                    .tenantId(tenant.getTenantId())
                    .documentTypeId(type.getDocumentTypeId())
                    .title("Watermark subject")
                    .currentVersionId(versionId)
                    .latestVersionId(versionId)
                    .ownerUserId(user.getUserId())
                    .createdBy(user.getUserId())
                    .build());
        });

        return new Fixture(tenant.getTenantId(), user.getUserId(), user.getUsername(), documentId, versionId, size);
    }

    /** A second version, so "which version was pinned" means something. */
    protected UUID addVersion(Fixture fixture, int number) {
        UUID versionId = UUID.randomUUID();
        DocumentVersion first = versionRepository.findById(fixture.versionId()).orElseThrow();
        UUID staging = jdbc.queryForObject(
                "select staging_location_id from scan_results where document_version_id = ?",
                UUID.class, fixture.versionId());
        UUID typeId = documentRepository.findById(fixture.documentId()).orElseThrow().getDocumentTypeId();

        transactions.executeWithoutResult(status -> {
            seedCleanScanResult(fixture.tenantId(), fixture.userId(), typeId, versionId,
                    first.getOriginalFilename(), first.getMimeType(), first.getFileHash(), staging);
            versionRepository.save(DocumentVersion.builder()
                    .versionId(versionId)
                    .documentId(fixture.documentId())
                    .tenantId(fixture.tenantId())
                    .versionNumber(number)
                    .majorVersion(first.getMajorVersion())
                    .minorVersion(first.getMinorVersion())
                    .storageLocationId(first.getStorageLocationId())
                    .storageObjectKey(first.getStorageObjectKey())
                    .originalFilename(first.getOriginalFilename())
                    .mimeType(first.getMimeType())
                    .sizeBytes(first.getSizeBytes())
                    .fileHash(first.getFileHash())
                    .createdReason("replacement_upload")
                    .scanStatus(DocumentVersion.SCAN_STATUS_CLEAN)
                    .createdBy(fixture.userId())
                    .build());
            Document document = documentRepository.findById(fixture.documentId()).orElseThrow();
            document.setLatestVersionId(versionId);
            document.setUpdatedAt(OffsetDateTime.now());
            documentRepository.save(document);
        });
        return versionId;
    }

    /** The scan_results row the database trigger insists on - see the class comment. */
    private void seedCleanScanResult(UUID tenantId, UUID userId, UUID documentTypeId, UUID versionId,
                                     String filename, String mimeType, String fileHash, UUID stagingLocationId) {
        UUID engineId = jdbc.queryForObject(
                "select scan_engine_id from scan_engines where is_deleted = false order by tenant_id nulls first "
                        + "limit 1", UUID.class);
        jdbc.update("""
                        insert into scan_results (scan_result_id, tenant_id, scan_engine_id, staging_location_id,
                            original_filename, mime_type, status, scan_mode, attempt_count, uploaded_by,
                            ingestion_kind, created_reason, document_type_id, title, document_version_id,
                            file_hash, engine_name, completed_at)
                        values (?, ?, ?, ?, ?, ?, 'clean', 'pre_store_stream', 1, ?, 'new_document',
                            'initial_upload', ?, ?, ?, ?, 'test-harness', now())
                        """,
                UUID.randomUUID(), tenantId, engineId, stagingLocationId, filename, mimeType, userId,
                documentTypeId, "Watermark subject", versionId, fileHash);
    }

    protected int countJobsFor(UUID documentId) {
        Integer count = jdbc.queryForObject("select count(*) from watermark_jobs where document_id = ?",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }

    protected UUID onlyJobFor(UUID documentId) {
        return jdbc.queryForObject("select job_id from watermark_jobs where document_id = ?", UUID.class, documentId);
    }

    protected int auditCount(String action, UUID objectId) {
        Integer count = jdbc.queryForObject("select count(*) from audit_events where action = ? and object_id = ?",
                Integer.class, action, objectId);
        return count == null ? 0 : count;
    }

    protected static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The smallest thing PDFBox will open - enough for a request-side test. */
    protected static byte[] minimalPdf() {
        return """
                %PDF-1.4
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj
                trailer<</Root 1 0 R>>
                %%EOF
                """.getBytes(StandardCharsets.ISO_8859_1);
    }
}
