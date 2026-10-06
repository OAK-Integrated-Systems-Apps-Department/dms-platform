package com.oak.dms.watermark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ResourceNotFoundException;
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
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.dto.WatermarkRequest;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import com.oak.dms.watermark.service.WatermarkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request side of watermarking: everything that happens before a worker
 * is involved.
 *
 * <h2>How documents are seeded</h2>
 * Rows are inserted directly and the bytes pushed through the real storage
 * layer into a {@code @TempDir} FILE_SYSTEM location, with
 * {@code scan_status = 'clean'}. That skips the malware scanner - which
 * {@code DocumentUploadIT} needs a live ClamAV for - while still exercising
 * the real {@code VersionContent} scan gate, the real encryption layer and
 * the real access checks. Nothing about the watermark path is mocked.
 */
@SpringBootTest(properties = {
        "dms.watermark.enabled=true",
        // Raised from the 8pt default purely to make the "too long to fit"
        // refusal deterministic here. The shrink floor works out independent
        // of the requested font size - a bigger font shrinks proportionally
        // further - so with a 200-character cap and an A4 page the default
        // sits within a pixel of the boundary. The floor's real behaviour,
        // including that it means the same at any DPI, is covered properly
        // in WatermarkRendererTest; this value only moves the line far
        // enough that the assertion here is about the guard running at
        // REQUEST time rather than about rounding.
        "dms.watermark.style.min-font-size=20"})
class WatermarkServiceIT {

    private static final UUID SYSTEM_CONFIDENTIAL = UUID.fromString("d0cc0000-0000-4000-8000-000000000001");
    private static final String PDF_MIME = "application/pdf";

    @Autowired
    private WatermarkService watermarkService;
    @Autowired
    private WatermarkJobRepository jobRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private DocumentVersionRepository versionRepository;
    @Autowired
    private DocumentTypeRepository documentTypeRepository;
    @Autowired
    private StorageLocationRepository storageLocationRepository;
    @Autowired
    private ObjectStorageServiceFactory storage;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private org.springframework.transaction.support.TransactionTemplate transactions;

    @TempDir
    Path tempDir;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------ happy path

    @Test
    void aTemplateRequestCreatesAPendingJobWithTheResolvedTextFrozenOntoIt() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(SYSTEM_CONFIDENTIAL, null, null, null, null, null, null, null, null), null);

        assertThat(response.status()).isEqualTo(WatermarkJobStatus.pending);
        assertThat(response.text()).isEqualTo("CONFIDENTIAL");
        assertThat(response.templateId()).isEqualTo(SYSTEM_CONFIDENTIAL);
        assertThat(response.templateVersion()).isNotNull();
        assertThat(response.downloadAvailable()).isFalse();

        WatermarkJob stored = jobRepository.findById(response.jobId()).orElseThrow();
        assertThat(stored.getVersionId()).isEqualTo(fixture.versionId());
        assertThat(stored.getSourceMime()).isEqualTo(PDF_MIME);
        assertThat(stored.getSourceSizeBytes()).isEqualTo(fixture.sizeBytes());
        assertThat(stored.getCorrelationId()).isNotNull();
    }

    @Test
    void customTextIsAccepted_andPlaceholdersAreResolvedOnTheRequestThread() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                request("{username} - {documentVersion} - {watermarkId}"), null);

        // Every token substituted before the row was written. The worker
        // reads this snapshot and never re-resolves.
        assertThat(response.text())
                .contains(fixture.username())
                .contains(" - 1 - ")
                .contains(response.jobId().toString().substring(0, 8));
        assertThat(spec(response.jobId()).text()).isEqualTo(response.text());
    }

    @Test
    void theWatermarkIdDrawnOnThePageIsTheIdThatGetsStored() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                request("{watermarkId}"), null);

        // The whole point of the assigned id: what a reader of a leaked copy
        // types in has to find the job.
        assertThat(response.jobId().toString()).startsWith(response.text());
    }

    @Test
    void theStylePrecedenceIsRequestThenTemplateThenSystemDefault() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(SYSTEM_CONFIDENTIAL, null, null, 120, null, null, "#FF0000", null, null),
                null);

        WatermarkSpec spec = spec(response.jobId());
        assertThat(spec.fontSize()).isEqualTo(120);          // from the request
        assertThat(spec.color()).isEqualTo("#ff0000");       // from the request, normalised
        assertThat(spec.opacity()).isEqualTo(25);            // system default
        assertThat(spec.placement().getValue()).isEqualTo("tiled");
    }

    @Test
    void anExplicitVersionIsPinnedOntoTheJobRatherThanWhateverIsCurrentLater() {
        Fixture fixture = fixture();
        UUID olderVersion = addVersion(fixture, 2);

        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(null, "DRAFT", fixture.versionId(), null, null, null, null, null, null), null);

        assertThat(response.versionId()).isEqualTo(fixture.versionId()).isNotEqualTo(olderVersion);
    }

    @Test
    void theRequestIsAuditedOnTheRequestThreadWithTheResolvedText() {
        Fixture fixture = fixture();
        WatermarkJobResponse response = watermarkService.apply(fixture.documentId(),
                request("CONFIDENTIAL - {username}"), null);

        assertThat(auditCount("WATERMARK_REQUESTED", response.jobId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select module from audit_events where action = ? and object_id = ?",
                String.class, "WATERMARK_REQUESTED", response.jobId())).isEqualTo("DW");
        // The text is in the audit entry, so the trail records what went on
        // the page - the worker never re-resolves it.
        assertThat(jdbc.queryForObject("select new_value::text from audit_events "
                        + "where action = ? and object_id = ?",
                String.class, "WATERMARK_REQUESTED", response.jobId()))
                .contains(fixture.username());
    }

    // ------------------------------------------------------- validation

    @Test
    void neitherTemplateNorTextIsRejected_andSoIsBoth() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(null, null, null, null, null, null, null, null, null), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("Choose a watermark template");

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(SYSTEM_CONFIDENTIAL, "DRAFT", null, null, null, null, null, null, null), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void anUnknownPlaceholderIsRejectedRatherThanPrintedLiterally() {
        // A typo left as-is would put the typo on somebody's document, and a
        // user who believed they were stamping their name on a copy would be
        // stamping nothing identifying at all.
        Fixture fixture = fixture();
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), request("HELLO {usernmae}"), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("{usernmae}")
                .hasMessageContaining("{username}");
    }

    @Test
    void textNoFontCanDrawIsRejectedAtRequestTimeWithoutCreatingAJob() {
        // The refusal the user can act on, delivered before a 202 - not as a
        // job that fails ten seconds later.
        //
        // The text has to be a REAL script with no font, not private-use
        // codepoints: TextSanitizer strips those before the font is ever
        // consulted, which is correct - an invisible or undefined character
        // has no business on a watermark - but it means they cannot be used
        // to reach this path. Egyptian hieroglyphs are a real script that
        // almost no server has a font for; where one does, there is nothing
        // to test and the assumption skips.
        String hieroglyphs = "REPORT 𓀀𓀁";
        org.junit.jupiter.api.Assumptions.assumeTrue(noFontCanDraw(hieroglyphs),
                "this JVM has a font for the script, so there is no refusal to test");

        Fixture fixture = fixture();
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), request(hieroglyphs), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("empty boxes")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(jobRepository.findAll()).noneMatch(j -> j.getDocumentId().equals(fixture.documentId()));
    }

    private static boolean noFontCanDraw(String text) {
        for (String family : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(java.util.Locale.ROOT)) {
            if (new java.awt.Font(family, java.awt.Font.BOLD, 12).canDisplayUpTo(text) < 0) {
                return false;
            }
        }
        return true;
    }

    @Test
    void textTooLongToFitThePageIsAlsoRejectedUpFront() {
        Fixture fixture = fixture();
        // Long enough that shrinking it to fit A4 would take the text below
        // the 8pt floor - at which point a refusal is more use than a mark
        // nobody can read.
        String tooLong = "CONFIDENTIAL - DO NOT DISTRIBUTE OUTSIDE THE COMPANY - PROPERTY OF THE ORGANISATION - "
                + "ANY UNAUTHORISED COPYING OR FORWARDING OF THIS DOCUMENT IS A BREACH OF POLICY AND WILL BE "
                + "INVESTIGATED BY THE COMPLIANCE TEAM";
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(null, tooLong, null, 200, null, null, null, null, null), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("too long to fit");
    }

    @Test
    void anUnsupportedFormatIsA415AndNeverReachesAJob() {
        Fixture fixture = fixture("notes.txt", "text/plain");
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), request("DRAFT"), null))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(jobRepository.findAll()).noneMatch(j -> j.getDocumentId().equals(fixture.documentId()));
    }

    @Test
    void aFileOverTheFormatsSizeCapIsRefusedFromTheVersionRowWithoutReadingIt() {
        Fixture fixture = fixture("huge.pdf", PDF_MIME, 400L * 1024 * 1024);
        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), request("DRAFT"), null))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void aRetiredTemplateCannotBeUsed() {
        Fixture fixture = fixture();
        UUID retired = retiredTemplate(fixture.tenantId());

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(),
                new WatermarkRequest(retired, null, null, null, null, null, null, null, null), null))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("retired");
    }

    @Test
    void anotherTenantsDocumentIsA404() {
        Fixture owner = fixture();
        Fixture stranger = fixture();
        TenantContext.clear();
        TenantContext.set(stranger.tenantId(), stranger.userId());

        assertThatThrownBy(() -> watermarkService.apply(owner.documentId(), request("DRAFT"), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theActiveJobCapIsEnforced() {
        Fixture fixture = fixture();
        watermarkService.apply(fixture.documentId(), request("ONE"), null);
        watermarkService.apply(fixture.documentId(), request("TWO"), null);

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), request("THREE"), null))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ------------------------------------------------------ idempotency

    @Test
    void theSameKeyAndBodyReturnsTheSameJobRatherThanASecondCopy() {
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(), request("DRAFT"), "click-1");
        WatermarkJobResponse second = watermarkService.apply(fixture.documentId(), request("DRAFT"), "click-1");

        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(countJobsFor(fixture.documentId())).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsA409() {
        Fixture fixture = fixture();
        watermarkService.apply(fixture.documentId(), request("DRAFT"), "click-1");

        assertThatThrownBy(() -> watermarkService.apply(fixture.documentId(), request("FINAL"), "click-1"))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theHashIgnoresTheMovingPlaceholdersSoAnHonestRetryIsNotA409() {
        // {date} and {watermarkId} differ on every attempt. Hashing the
        // RESOLVED text would make a client that retries with a stable key -
        // which is exactly what a well-behaved client does - get a 409.
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(),
                request("CONFIDENTIAL {date} {watermarkId}"), "retry-me");
        WatermarkJobResponse second = watermarkService.apply(fixture.documentId(),
                request("CONFIDENTIAL {date} {watermarkId}"), "retry-me");

        assertThat(second.jobId()).isEqualTo(first.jobId());
    }

    @Test
    void twoSimultaneousRequestsWithOneKeyProduceOneJob_andBothCallersGetIt() throws Exception {
        // The race the partial unique index exists for. The loser must come
        // back with the WINNER'S job, not an error: from the user's side one
        // click produced one job, which is what the key promised.
        Fixture fixture = fixture();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<WatermarkJobResponse>> calls = java.util.Collections.nCopies(threads, () -> {
                TenantContext.set(fixture.tenantId(), fixture.userId());
                try {
                    return watermarkService.apply(fixture.documentId(), request("DRAFT"), "one-click");
                } finally {
                    TenantContext.clear();
                }
            });

            List<Future<WatermarkJobResponse>> futures = pool.invokeAll(calls, 60, TimeUnit.SECONDS);
            java.util.Set<UUID> jobIds = new java.util.HashSet<>();
            int succeeded = 0;
            for (Future<WatermarkJobResponse> future : futures) {
                try {
                    jobIds.add(future.get().jobId());
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException e) {
                    // A caller may still lose on the serialisation level;
                    // what must never happen is two DIFFERENT jobs.
                    assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
                }
            }

            assertThat(succeeded).describedAs("at least one caller must succeed").isGreaterThan(0);
            assertThat(jobIds).describedAs("every successful caller must get the same job").hasSize(1);
            assertThat(countJobsFor(fixture.documentId()))
                    .describedAs("exactly one row for one key").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedJobReleasesItsKeySoARetryStartsAfresh() {
        Fixture fixture = fixture();
        WatermarkJobResponse first = watermarkService.apply(fixture.documentId(), request("DRAFT"), "retry");
        jobRepository.findById(first.jobId()).ifPresent(job -> {
            job.setStatus(WatermarkJobStatus.failed);
            job.setFailureReason("so this test can retry");
            jobRepository.saveAndFlush(job);
        });

        WatermarkJobResponse retried = watermarkService.apply(fixture.documentId(), request("DRAFT"), "retry");

        assertThat(retried.jobId()).isNotEqualTo(first.jobId());
        assertThat(retried.status()).isEqualTo(WatermarkJobStatus.pending);
    }

    // ---------------------------------------------------------- fixtures

    private WatermarkRequest request(String text) {
        return new WatermarkRequest(null, text, null, null, null, null, null, null, null);
    }

    private WatermarkSpec spec(UUID jobId) {
        try {
            return objectMapper.readValue(jobRepository.findById(jobId).orElseThrow().getRequestSnapshot(),
                    WatermarkSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int countJobsFor(UUID documentId) {
        Integer count = jdbc.queryForObject("select count(*) from watermark_jobs where document_id = ?",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }

    private int auditCount(String action, UUID objectId) {
        Integer count = jdbc.queryForObject("select count(*) from audit_events where action = ? and object_id = ?",
                Integer.class, action, objectId);
        return count == null ? 0 : count;
    }

    private UUID retiredTemplate(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into watermark_templates (template_id, tenant_id, label, default_text, active) "
                + "values (?, ?, ?, ?, false)", id, tenantId, "Retired " + id, "OLD");
        return id;
    }

    private record Fixture(UUID tenantId, UUID userId, String username, UUID documentId, UUID versionId,
                           long sizeBytes) {
    }

    private Fixture fixture() {
        return fixture("report.pdf", PDF_MIME);
    }

    private Fixture fixture(String filename, String mimeType) {
        return fixture(filename, mimeType, -1);
    }

    /**
     * A tenant, a user, a storage location in this test's own @TempDir, and
     * a document whose single version is marked scanned-clean - inserted
     * directly, so no ClamAV is needed, while the real storage, encryption
     * and access paths are all still exercised.
     */
    private Fixture fixture(String filename, String mimeType, long declaredSize) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Watermark request tenant " + UUID.randomUUID())
                .domain("watermark-request-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@watermark-request.invalid")
                .username("user-" + keycloakId.toString().substring(0, 8))
                .firstName("Test").lastName("Requester").displayName("Test Requester")
                .status(User.UserStatus.active)
                .build());

        StorageLocation location = storageLocationRepository.save(StorageLocation.builder()
                .tenantId(tenant.getTenantId())
                .name("wm-test-" + UUID.randomUUID())
                .provider(StorageProvider.FILE_SYSTEM)
                .purpose(StoragePurpose.document)
                .basePath(tempDir.resolve(tenant.getTenantId().toString()).toString())
                .status(StorageLocation.Status.active)
                .build());

        // The scan gate needs a staging location to point the seeded scan
        // result at - it is never read, only referenced.
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

        String typeSuffix = UUID.randomUUID().toString().substring(0, 8);
        DocumentType type = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenant.getTenantId())
                .typeCode("WM_" + typeSuffix)
                .typeName("Watermark test type " + typeSuffix)
                .build());

        byte[] content = minimalPdf();
        long size = declaredSize >= 0 ? declaredSize : content.length;
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String objectKey = tenant.getTenantId() + "/" + documentId + "/" + versionId;
        storage.resolve(location).upload(location, objectKey, new ByteArrayInputStream(content),
                content.length, mimeType);

        // documents and document_versions reference each other, and
        // documents.latest_version_id is NOT NULL - so neither row can be
        // written first. The schema solves this with DEFERRABLE INITIALLY
        // DEFERRED foreign keys, which means both inserts have to be in ONE
        // transaction: separate commits would each be checked on their own
        // and fail.
        transactions.executeWithoutResult(status -> {
        // The scan gate is enforced by a DATABASE TRIGGER
        // (document_versions_require_clean_scan), not only by application
        // code: no version row may exist without a clean scan_results row
        // carrying the same file hash. So seeding a document means seeding
        // its scan result too - there is no way to insert "around" the gate,
        // which is exactly the property that makes the gate worth having.
        seedCleanScanResult(tenant.getTenantId(), user.getUserId(), type.getDocumentTypeId(),
                documentId, versionId, filename, mimeType, sha256(content), stagingLocation);
        versionRepository.save(DocumentVersion.builder()
                .versionId(versionId)
                .documentId(documentId)
                .tenantId(tenant.getTenantId())
                .versionNumber(1)
                .storageLocationId(location.getStorageLocationId())
                .storageObjectKey(objectKey)
                .originalFilename(filename)
                .mimeType(mimeType)
                .sizeBytes(size)
                .fileHash(sha256(content))
                .createdReason("initial_upload")
                .majorVersion(1)
                .minorVersion(0)
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

    /**
     * The scan_results row the trigger insists on: status clean, the same
     * file hash as the version, linked to it. Written with JDBC because the
     * scanning entities are another module's and this test has no business
     * depending on them.
     */
    private void seedCleanScanResult(UUID tenantId, UUID userId, UUID documentTypeId, UUID documentId,
                                     UUID versionId, String filename, String mimeType, String fileHash,
                                     UUID stagingLocationId) {
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

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A second version, so "which version was pinned" means something. */
    private UUID addVersion(Fixture fixture, int number) {
        UUID versionId = UUID.randomUUID();
        DocumentVersion first = versionRepository.findById(fixture.versionId()).orElseThrow();
        UUID staging = jdbc.queryForObject("select staging_location_id from scan_results where document_version_id = ?",
                UUID.class, fixture.versionId());
        transactions.executeWithoutResult(status -> {
        seedCleanScanResult(fixture.tenantId(), fixture.userId(),
                documentRepository.findById(fixture.documentId()).orElseThrow().getDocumentTypeId(),
                fixture.documentId(), versionId, first.getOriginalFilename(), first.getMimeType(),
                first.getFileHash(), staging);
        versionRepository.save(DocumentVersion.builder()
                .versionId(versionId)
                .documentId(fixture.documentId())
                .tenantId(fixture.tenantId())
                .versionNumber(number)
                .storageLocationId(first.getStorageLocationId())
                .storageObjectKey(first.getStorageObjectKey())
                .originalFilename(first.getOriginalFilename())
                .mimeType(first.getMimeType())
                .sizeBytes(first.getSizeBytes())
                .fileHash(first.getFileHash())
                .createdReason("replacement_upload")
                .majorVersion(first.getMajorVersion())
                .minorVersion(first.getMinorVersion())
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

    /** The smallest thing PDFBox will open - enough for a request-side test. */
    private static byte[] minimalPdf() {
        String pdf = """
                %PDF-1.4
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj
                trailer<</Root 1 0 R>>
                %%EOF
                """;
        return pdf.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
