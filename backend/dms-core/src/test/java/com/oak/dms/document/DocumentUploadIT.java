package com.oak.dms.document;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the pluggable storage architecture end to end against the
 * {@code FILE_SYSTEM} provider (see docs/storage-architecture.md - this
 * repo has no Testcontainers, so a live S3-compatible server isn't part of
 * this suite; the S3 adapter's own wiring is covered at the unit level by
 * {@code S3ObjectStorageAdapterTest}). Each test registers its own
 * tenant-scoped {@link StorageLocation} pointing at a fresh {@code @TempDir}
 * rather than relying on the platform-default location {@code
 * StorageLocationBootstrap} seeds on startup, so this test's storage
 * location can never be affected by (or leak into) any other test or
 * app-startup run sharing this suite's Postgres instance - same reasoning
 * {@code PermissionCodeAuditIT}/{@code AccessDeniedAuditIT} already apply
 * to creating a fresh tenant/user per test rather than reusing shared rows.
 */
@SpringBootTest
class DocumentUploadIT {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentTypeRepository documentTypeRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StorageLocationRepository storageLocationRepository;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private com.oak.dms.storage.service.ObjectStorageServiceFactory objectStorageServiceFactory;
    @Autowired
    private com.oak.dms.encryption.TenantKeyShredder shredder;
    @Autowired
    private DocumentVersionService versionService;
    @Autowired
    private RecycleBinService recycleBin;
    @Autowired
    private com.oak.dms.folder.FolderService folderService;
    @Autowired
    private OcrArtifactWriter ocrArtifactWriter;
    @Autowired
    private DocumentVersionRepository documentVersionRepository;
    @Autowired
    private com.oak.dms.tenant.TenantService tenantService;

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void uploadDownloadAndDelete_roundTripsTheOriginalBytesThroughFileSystemStorage() throws Exception {
        TenantFixture tenant = createTenantWithFileSystemStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());

        byte[] content = "hello pluggable storage".getBytes(StandardCharsets.UTF_8);
        DocumentResponse uploaded = uploadClean(documentTypeId, "Test Doc", "hello.txt", content);

        assertThat(uploaded.title()).isEqualTo("Test Doc");
        assertThat(uploaded.status()).isEqualTo("draft");
        assertThat(uploaded.currentVersionId()).isEqualTo(uploaded.latestVersionId());

        DownloadedDocument downloaded = documentService.downloadDocument(uploaded.documentId());
        byte[] roundTripped;
        try (var in = downloaded.content()) {
            roundTripped = in.readAllBytes();
        }
        assertThat(roundTripped).isEqualTo(content);
        assertThat(downloaded.filename()).isEqualTo("hello.txt");
        assertThat(downloaded.contentType()).isEqualTo("text/plain");
        assertThat(downloaded.sizeBytes()).isEqualTo(content.length);
        // Encrypted at rest: what storage itself holds is not the file (Module DE).
        assertThat(storedBytes(uploaded.documentId())).isNotEqualTo(content);
        // The download is audited - it used to be dropped by a read-only transaction.
        assertThat(auditCount("DOCUMENT_DOWNLOADED", uploaded.documentId())).isEqualTo(1);
        try (var in = documentService.previewDocument(uploaded.documentId()).content()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(auditCount("DOCUMENT_VIEWED", uploaded.documentId())).isEqualTo(1);

        documentService.deleteDocument(uploaded.documentId());
        assertThatThrownBy(() -> documentService.getDocument(uploaded.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cryptoShredding_aRemovedTenantsDocumentsCantBeReadAgain_andOnlyOnceItIsRemoved() throws Exception {
        TenantFixture tenant = createTenantWithFileSystemStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());
        byte[] content = "a contract that must not outlive the customer".getBytes(StandardCharsets.UTF_8);
        DocumentResponse doc = uploadClean(documentTypeId, "Contract", "contract.txt", content);
        // OCR output is the contract's text. It must be shredded with
        // everything else, not left as the one readable copy - which it would
        // be if it were stored outside the encryption layer, as it was when
        // the worker owned its own bucket.
        DocumentVersion version = jdbcVersion(doc.documentId());
        String ocrKey = ocrArtifactWriter.write(version, OcrArtifactWriter.Artifact.output,
                new ByteArrayInputStream(content), content.length, "application/json");
        StorageLocation ocrLocation = storageLocationRepository.findById(version.getStorageLocationId()).orElseThrow();
        TenantContext.clear();
        String name = tenantRepository.findById(tenant.tenantId()).orElseThrow().getTenantName();

        // Still an active organisation: refused.
        assertThatThrownBy(() -> shredder.shred(tenant.tenantId(), name, "offboarded", false))
                .hasMessageContaining("Deactivate the organisation first");

        tenantService.deactivateTenant(tenant.tenantId());
        assertThatThrownBy(() -> shredder.shred(tenant.tenantId(), "not the name", "offboarded", false))
                .hasMessageContaining("name exactly");

        var result = shredder.shred(tenant.tenantId(), name, "offboarded", false);

        assertThat(result.wrappedKeysDeleted()).isPositive();
        assertThat(result.destroyedKeys()).contains("local-dev:" + tenant.tenantId());
        assertThat(result.unencryptedFilesLeft()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from storage_object_keys where tenant_id = ? "
                + "and (shredded_at is null or wrapped_key <> 'shredded')", Integer.class, tenant.tenantId())).isZero();
        // The bytes are still in storage - and are only ciphertext no key can open.
        assertThat(storedBytes(doc.documentId())).isNotEqualTo(content);
        // Same for the OCR output: its wrapped key was covered by the
        // tenant-wide shred (it went through the encrypting layer, so it has a
        // storage_object_keys row at all), and what is left on disk is not the
        // text.
        assertThat(jdbc.queryForObject("select wrapped_key from storage_object_keys where storage_location_id = ? "
                + "and object_key = ?", String.class, version.getStorageLocationId(), ocrKey)).isEqualTo("shredded");
        try (var raw = objectStorageServiceFactory.resolveUnencrypted(ocrLocation)
                .download(ocrLocation, ocrKey).content()) {
            assertThat(raw.readAllBytes()).isNotEqualTo(content);
        }
        TenantContext.set(tenant.tenantId(), tenant.userId());
        // Fails plainly - never the ciphertext served as if it were the file.
        assertThatThrownBy(() -> {
            try (var in = documentService.downloadDocument(doc.documentId()).content()) {
                in.readAllBytes();
            }
        }).hasMessageContaining("encryption key was destroyed");
        TenantContext.clear();
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action = "
                + "'ENCRYPTION_TENANT_KEYS_SHREDDED' and object_id = ? and outcome = 'SUCCESS'",
                Integer.class, tenant.tenantId())).isEqualTo(1);
        assertThatThrownBy(() -> shredder.shred(tenant.tenantId(), name, "again", false))
                .hasMessageContaining("already destroyed");
    }

    @Test
    void versionHistory_newVersionsBecomeCurrent_olderOnesStayDownloadable_andARestoreIsANewVersion() throws Exception {
        TenantFixture tenant = createTenantWithFileSystemStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());
        byte[] first = "contract, first draft".getBytes(StandardCharsets.UTF_8);
        byte[] second = "contract, signed".getBytes(StandardCharsets.UTF_8);
        DocumentResponse doc = uploadClean(documentTypeId, "Contract", "contract.txt", first);

        IngestionTicketResponse ticket = versionService.upload(doc.documentId(), "contract-signed.txt", second.length,
                new ByteArrayInputStream(second), "  Signed by both parties  ");
        assertThat(ticket.status()).as(ticket.message()).isEqualTo(ScanResultStatus.clean);

        var history = versionService.list(doc.documentId());
        assertThat(history).extracting(DocumentVersionService.VersionItem::versionLabel).containsExactly("1.1", "1.0");
        assertThat(history.get(0).current()).isTrue();
        assertThat(history.get(0).versionComment()).isEqualTo("Signed by both parties");
        assertThat(history.get(0).createdByName()).isNotBlank();
        // The document now opens on the new version; the old one is still there.
        try (var in = documentService.downloadDocument(doc.documentId()).content()) {
            assertThat(in.readAllBytes()).isEqualTo(second);
        }
        UUID oldVersion = history.get(1).versionId();
        try (var in = versionService.download(doc.documentId(), oldVersion).content()) {
            assertThat(in.readAllBytes()).isEqualTo(first);
        }

        // Restoring adds a new version with the old content - the history only grows.
        assertThat(versionService.restore(doc.documentId(), oldVersion).status()).isEqualTo(ScanResultStatus.clean);
        history = versionService.list(doc.documentId());
        assertThat(history).extracting(DocumentVersionService.VersionItem::versionLabel)
                .containsExactly("1.2", "1.1", "1.0");
        assertThat(history.get(0).createdReason()).isEqualTo("rollback");
        assertThat(history.get(0).versionComment()).isEqualTo("Restored from version 1.0");
        try (var in = documentService.downloadDocument(doc.documentId()).content()) {
            assertThat(in.readAllBytes()).isEqualTo(first);
        }
        UUID restored = history.get(0).versionId();
        assertThatThrownBy(() -> versionService.restore(doc.documentId(), restored))
                .hasMessageContaining("already the current version");
        assertThat(auditCount("DOCUMENT_VERSION_RESTORED", doc.documentId())).isEqualTo(1);
    }

    @Test
    void recycleBin_restoreBringsItBack_andAPurgeDestroysEveryVersionsFileAndKey() throws Exception {
        TenantFixture tenant = createTenantWithFileSystemStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());
        byte[] content = "minutes of the board meeting".getBytes(StandardCharsets.UTF_8);
        DocumentResponse doc = uploadClean(documentTypeId, "Minutes", "minutes.txt", content);
        // Different bytes for v2. This branch refuses a new version identical
        // to the current one (DuplicateUploadException) - correct, and not
        // what this test is about: it needs two versions, each with its own
        // stored object and key, to prove the purge destroys both.
        byte[] revised = "minutes of the board meeting, as amended".getBytes(StandardCharsets.UTF_8);
        versionService.upload(doc.documentId(), "minutes-v2.txt", revised.length,
                new ByteArrayInputStream(revised), null);

        documentService.deleteDocument(doc.documentId());
        var bin = recycleBin.list();
        assertThat(bin.retentionDays()).isEqualTo(30);
        assertThat(bin.items()).extracting(RecycleBinService.RecycleBinItem::documentId).contains(doc.documentId());
        var item = bin.items().stream().filter(i -> i.documentId().equals(doc.documentId())).findFirst().orElseThrow();
        assertThat(item.purgeAt()).isEqualTo(item.deletedAt().plusDays(30));

        recycleBin.restore(doc.documentId());
        assertThat(documentService.getDocument(doc.documentId()).title()).isEqualTo("Minutes");

        documentService.deleteDocument(doc.documentId());
        var keys = jdbc.queryForList("select v.storage_location_id, v.storage_object_key from document_versions v "
                + "where v.document_id = ?", doc.documentId());
        assertThat(keys).hasSize(2);
        recycleBin.purgeNow(doc.documentId());

        // Every version's file and its encryption key are gone; the rows stay as tombstones.
        for (var row : keys) {
            StorageLocation location = storageLocationRepository.findById((UUID) row.get("storage_location_id"))
                    .orElseThrow();
            assertThat(objectStorageServiceFactory.resolveUnencrypted(location)
                    .exists(location, (String) row.get("storage_object_key"))).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from storage_object_keys where storage_location_id = ? "
                    + "and object_key = ?", Integer.class, row.get("storage_location_id"),
                    row.get("storage_object_key"))).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from document_versions where document_id = ? "
                + "and purged_at is not null", Integer.class, doc.documentId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select purged_at is not null from documents where document_id = ?",
                Boolean.class, doc.documentId())).isTrue();
        assertThat(recycleBin.list().items()).extracting(RecycleBinService.RecycleBinItem::documentId)
                .doesNotContain(doc.documentId());
        assertThatThrownBy(() -> recycleBin.restore(doc.documentId())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(auditCount("DOCUMENT_PURGED", doc.documentId())).isEqualTo(1);
    }

    /**
     * The recycle bin's gap fixes (module RBR): the owner is told of a delete and a purge; a document whose folder was
     * deleted after it restores to the top level; emptying the bin purges what it may and keeps a legal hold; and a held
     * document past its retention period is flagged once, not purged.
     */
    @Test
    void recycleBin_notifiesTheOwner_restoresToTopLevel_emptiesAroundAHold_andFlagsAHeldDocument() throws Exception {
        TenantFixture tenant = createTenantWithFileSystemStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());
        UUID folderId = folderService.create("Board", null).folderId();
        byte[] content = "agenda".getBytes(StandardCharsets.UTF_8);
        IngestionTicketResponse ticket = documentService.uploadDocument(documentTypeId, "Agenda", "agenda.txt",
                content.length, new ByteArrayInputStream(content), folderId);
        UUID filed = ticket.documentId();

        documentService.deleteDocument(filed);
        assertThat(jdbc.queryForObject("select count(*) from notifications where category_code = "
                + "'recycle-bin.deleted.owner' and object_id = ?", Integer.class, filed)).isEqualTo(1);

        // Its folder goes after it (the folder is empty now) - restoring must not be refused.
        folderService.delete(folderId);
        assertThat(recycleBin.list().items()).filteredOn(i -> i.documentId().equals(filed))
                .singleElement().satisfies(i -> assertThat(i.folderGone()).isTrue());
        assertThat(recycleBin.restore(filed).restoredToTopLevel()).isTrue();
        assertThat(jdbc.queryForObject("select folder_id from documents where document_id = ?", UUID.class, filed))
                .isNull();

        // Empty the bin with one ordinary and one legally held document in it.
        UUID plain = uploadClean(documentTypeId, "Plain", "plain.txt", "plain".getBytes(StandardCharsets.UTF_8))
                .documentId();
        UUID held = uploadClean(documentTypeId, "Held", "held.txt", "held".getBytes(StandardCharsets.UTF_8))
                .documentId();
        documentService.deleteDocument(plain);
        documentService.deleteDocument(held);
        jdbc.update("update documents set legal_hold = true, legal_hold_at = now() where document_id = ?", held);
        assertThat(recycleBin.list().usage().documents()).isEqualTo(2);
        var result = recycleBin.emptyBin();
        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(auditCount("DOCUMENT_PURGED", plain)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select purged_at is null from documents where document_id = ?",
                Boolean.class, held)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from notifications where category_code = "
                + "'recycle-bin.purged.owner' and object_id = ?", Integer.class, plain)).isEqualTo(1);

        // Past its retention period, the held one is flagged - once - and still not purged.
        jdbc.update("update documents set deleted_at = now() - interval '40 days' where document_id = ?", held);
        recycleBin.purgeExpired();
        recycleBin.purgeExpired();
        assertThat(auditCount("RECYCLE_BIN_HOLD_FLAGGED", held)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select purged_at is null from documents where document_id = ?",
                Boolean.class, held)).isTrue();

        // An ordinary one inside the warning window is warned once.
        UUID soon = uploadClean(documentTypeId, "Soon", "soon.txt", "soon".getBytes(StandardCharsets.UTF_8))
                .documentId();
        documentService.deleteDocument(soon);
        jdbc.update("update documents set deleted_at = now() - interval '28 days' where document_id = ?", soon);
        recycleBin.purgeExpired();
        recycleBin.purgeExpired();
        assertThat(jdbc.queryForObject("select count(*) from notifications where category_code = "
                + "'recycle-bin.purge-soon.owner' and object_id = ?", Integer.class, soon)).isEqualTo(1);
    }

    /**
     * A purge has to take everything OCR derived from the document with it,
     * not just the original file.
     *
     * <p>The reason this needs its own test rather than riding on the one
     * above: OCR output <em>is</em> the document's text and a searchable PDF
     * <em>is</em> the document, under different keys. A purge that destroyed
     * the original and left those behind would report success while leaving a
     * readable copy of a permanently deleted document in storage - the exact
     * failure the whole key-ownership design in {@link OcrArtifactWriter}
     * exists to prevent, and one no existing assertion would have caught.
     *
     * <p>Every artifact and row created here is a real one, written through
     * the same code path the worker's PUT uses, so the test fails if that
     * path ever starts choosing keys the purge cannot derive.
     */
    @Test
    void aPurge_destroysTheOcrOutputAndSearchablePdfAndEveryRowDerivedFromThem() throws Exception {
        TenantFixture tenant = createTenantWithFileSystemStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());
        byte[] content = "invoice total 412.00".getBytes(StandardCharsets.UTF_8);
        DocumentResponse doc = uploadClean(documentTypeId, "Invoice", "invoice.txt", content);
        DocumentVersion version = jdbcVersion(doc.documentId());

        String outputKey = ocrArtifactWriter.write(version, OcrArtifactWriter.Artifact.output,
                new ByteArrayInputStream("{\"text\":\"invoice total 412.00\"}".getBytes(StandardCharsets.UTF_8)),
                29, "application/json");
        String pdfKey = ocrArtifactWriter.write(version, OcrArtifactWriter.Artifact.searchable_pdf,
                new ByteArrayInputStream("%PDF-1.4 searchable".getBytes(StandardCharsets.UTF_8)),
                19, "application/pdf");
        seedOcrRows(tenant, doc.documentId(), version.getVersionId());

        StorageLocation location = storageLocationRepository.findById(version.getStorageLocationId()).orElseThrow();
        assertThat(objectStorageServiceFactory.resolveUnencrypted(location).exists(location, outputKey)).isTrue();
        assertThat(objectStorageServiceFactory.resolveUnencrypted(location).exists(location, pdfKey)).isTrue();

        documentService.deleteDocument(doc.documentId());
        recycleBin.purgeNow(doc.documentId());

        // The objects themselves, and the wrapped key each was encrypted
        // with - the same two assertions the original file gets.
        for (String key : java.util.List.of(outputKey, pdfKey)) {
            assertThat(objectStorageServiceFactory.resolveUnencrypted(location).exists(location, key))
                    .as("OCR artifact %s survived the purge", key).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from storage_object_keys where storage_location_id = ? "
                    + "and object_key = ?", Integer.class, version.getStorageLocationId(), key)).isZero();
        }

        // And every row that records or derives from them. Listed one by one
        // rather than looped, so a failure names the table that leaked.
        UUID versionId = version.getVersionId();
        assertThat(rowCount("ocr_outputs", versionId)).isZero();
        assertThat(rowCount("renditions", versionId)).isZero();
        assertThat(rowCount("document_ocr_field_results", versionId)).isZero();
        assertThat(rowCount("document_ocr_line_items", versionId)).isZero();
        assertThat(rowCount("document_ocr_corrections", versionId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from processing_jobs where reference_type = 'document_versions' "
                + "and reference_id = ?", Integer.class, versionId)).isZero();
    }

    private int rowCount(String table, UUID versionId) {
        return jdbc.queryForObject("select count(*) from " + table + " where version_id = ?", Integer.class, versionId);
    }

    /**
     * One row in each table the review screen reads, so the purge has
     * something to fail to delete in every one of them.
     */
    private void seedOcrRows(TenantFixture tenant, UUID documentId, UUID versionId) {
        jdbc.update("insert into document_ocr_field_results (tenant_id, document_id, version_id, field_key, value, "
                + "confidence, status) values (?, ?, ?, 'total', '412.00', 0.91, 'extracted')",
                tenant.tenantId(), documentId, versionId);
        jdbc.update("insert into document_ocr_line_items (tenant_id, document_id, version_id, line_number, "
                + "description, line_total) values (?, ?, ?, 0, 'Consulting', 412.00)",
                tenant.tenantId(), documentId, versionId);
        jdbc.update("insert into document_ocr_corrections (tenant_id, document_id, version_id, field_key, action, "
                + "original_value, corrected_value, corrected_by) "
                + "values (?, ?, ?, 'total', 'accepted', '412.00', '412.00', ?)",
                tenant.tenantId(), documentId, versionId, tenant.userId());
        jdbc.update("insert into processing_jobs (tenant_id, job_type, reference_type, reference_id, status) "
                + "values (?, 'ocr', 'document_versions', ?, 'completed')", tenant.tenantId(), versionId);
    }

    /** The document's current version, as the purge path will see it. */
    private DocumentVersion jdbcVersion(UUID documentId) {
        UUID versionId = jdbc.queryForObject("select current_version_id from documents where document_id = ?",
                UUID.class, documentId);
        return documentVersionRepository.findById(versionId).orElseThrow();
    }

    @Test
    void theSweep_purgesWhatsOutlivedItsRetention_butNeverAProtectedVersion_norARecentDelete() {
        TenantFixture tenant = createTenantWithFileSystemStorage();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        UUID documentTypeId = createDocumentType(tenant.tenantId());
        byte[] content = "old".getBytes(StandardCharsets.UTF_8);
        UUID expired = uploadClean(documentTypeId, "Expired", "a.txt", content).documentId();
        UUID held = uploadClean(documentTypeId, "Held", "b.txt", content).documentId();
        UUID recent = uploadClean(documentTypeId, "Recent", "c.txt", content).documentId();
        for (UUID id : java.util.List.of(expired, held, recent)) {
            documentService.deleteDocument(id);
        }
        assertThat(recycleBin.setRetentionDays(7)).isEqualTo(7);
        jdbc.update("update documents set deleted_at = now() - interval '8 days' where document_id in (?, ?)",
                expired, held);
        jdbc.update("update document_versions set is_protected = true where document_id = ?", held);
        TenantContext.clear(); // the sweep runs with no one signed in

        recycleBin.purgeExpired();

        assertThat(purged(expired)).isTrue();
        assertThat(purged(held)).isFalse();
        assertThat(purged(recent)).isFalse();
        TenantContext.set(tenant.tenantId(), tenant.userId());
        assertThatThrownBy(() -> recycleBin.purgeNow(held)).hasMessageContaining("protected version");
        assertThat(jdbc.queryForObject("select actor_type from audit_events where action = 'DOCUMENT_PURGED' "
                + "and object_id = ?", String.class, expired)).isEqualTo("SYSTEM");
    }

    private boolean purged(UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select purged_at is not null from documents "
                + "where document_id = ?", Boolean.class, documentId));
    }

    @Test
    void crossTenantAccess_anotherTenantsDocumentLooksLikeNotFound() {
        TenantFixture tenantA = createTenantWithFileSystemStorage();
        TenantContext.set(tenantA.tenantId(), tenantA.userId());
        UUID documentTypeIdA = createDocumentType(tenantA.tenantId());
        byte[] content = "tenant A's private content".getBytes(StandardCharsets.UTF_8);
        DocumentResponse doc = uploadClean(documentTypeIdA, "Tenant A Doc", "a.txt", content);
        TenantContext.clear();

        TenantFixture tenantB = createTenantWithFileSystemStorage();
        TenantContext.set(tenantB.tenantId(), tenantB.userId());

        assertThatThrownBy(() -> documentService.getDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> documentService.downloadDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> documentService.deleteDocument(doc.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadWithoutDocmCreatePermission_isForbidden() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        UUID tenantId = createTenantAndPermissionlessUser(keycloakId);

        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "data".getBytes());

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentTypeId", UUID.randomUUID().toString())
                        .param("title", "Test")
                        .with(jwt().jwt(builder -> builder.subject(keycloakId.toString())
                                .claim("tenant_id", tenantId.toString())).authorities()))
                .andExpect(status().isForbidden());
    }

    private TenantFixture createTenantWithFileSystemStorage() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Document upload test tenant " + UUID.randomUUID())
                .domain("document-upload-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@document-upload-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Uploader")
                .displayName("Test Uploader")
                .status(User.UserStatus.active)
                .build());

        storageLocationRepository.save(StorageLocation.builder()
                .tenantId(tenant.getTenantId())
                .name("test-filesystem-" + UUID.randomUUID())
                .provider(com.oak.dms.storage.enums.StorageProvider.FILE_SYSTEM)
                .basePath(tempDir.resolve(tenant.getTenantId().toString()).toString())
                .status(StorageLocation.Status.active)
                .build());

        // Staging and quarantine for the scan gate - tenant-scoped in the same
        // @TempDir, separate directories from the document location.
        for (StoragePurpose purpose : new StoragePurpose[]{StoragePurpose.staging, StoragePurpose.quarantine}) {
            storageLocationRepository.save(StorageLocation.builder()
                    .tenantId(tenant.getTenantId())
                    .name("test-" + purpose + "-" + UUID.randomUUID())
                    .provider(com.oak.dms.storage.enums.StorageProvider.FILE_SYSTEM)
                    .purpose(purpose)
                    .basePath(tempDir.resolve(tenant.getTenantId() + "-" + purpose).toString())
                    .status(StorageLocation.Status.active)
                    .build());
        }

        return new TenantFixture(tenant.getTenantId(), user.getUserId());
    }

    private UUID createDocumentType(UUID tenantId) {
        DocumentType documentType = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenantId)
                .typeCode("TEST_TYPE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .typeName("Test Document Type")
                .build());
        return documentType.getDocumentTypeId();
    }

    /** A real tenant + a real user with zero role/permission grants - no DOCM_* codes. */
    private UUID createTenantAndPermissionlessUser(UUID keycloakId) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Document upload forbidden test tenant " + keycloakId)
                .domain("document-upload-forbidden-test-" + keycloakId + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@document-upload-forbidden-test.invalid")
                .username(keycloakId.toString())
                .firstName("No")
                .lastName("Permissions")
                .displayName("No Permissions")
                .status(User.UserStatus.active)
                .build());

        return tenant.getTenantId();
    }

    /**
     * Uploads go through the malware scan gate and return an ingestion
     * ticket; with the local ClamAV engine a clean file is promoted before
     * the call returns. Needs clamd reachable (docker compose up clamav).
     */
    private DocumentResponse uploadClean(UUID documentTypeId, String title, String filename, byte[] content) {
        IngestionTicketResponse ticket = documentService.uploadDocument(documentTypeId, title, filename,
                content.length, new ByteArrayInputStream(content));
        assertThat(ticket.status()).as(ticket.message()).isEqualTo(ScanResultStatus.clean);
        return documentService.getDocument(ticket.documentId());
    }

    private record TenantFixture(UUID tenantId, UUID userId) {
    }

    /** The document's current version exactly as stored, without decryption. */
    private byte[] storedBytes(UUID documentId) throws java.io.IOException {
        var row = jdbc.queryForMap("select v.storage_location_id, v.storage_object_key from documents d "
                + "join document_versions v on v.version_id = d.current_version_id where d.document_id = ?", documentId);
        StorageLocation location = storageLocationRepository.findById((UUID) row.get("storage_location_id")).orElseThrow();
        try (var in = objectStorageServiceFactory.resolveUnencrypted(location)
                .download(location, (String) row.get("storage_object_key")).content()) {
            return in.readAllBytes();
        }
    }

    private int auditCount(String action, UUID objectId) {
        Integer count = jdbc.queryForObject("select count(*) from audit_events where action = ? and object_id = ?",
                Integer.class, action, objectId);
        return count == null ? 0 : count;
    }
}
