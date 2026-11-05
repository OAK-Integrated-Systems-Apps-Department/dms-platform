package com.oak.dms.document;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.FolderException;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.preview.PreviewPolicyService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Document previews and renditions (DPRM) end to end against the FILE_SYSTEM storage provider: pages drawn on the
 * server, thumbnails made once and kept encrypted, the organisation's preview policy, failures kept for the
 * administrator, and everything removed with the version's file.
 * <p>
 * Documents are made through the promoter fixtures, not the upload scan gate: this must run where there is no ClamAV
 * (CI), which is why DocumentUploadIT is excluded from it.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class DocumentRenditionIT {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentRenditionService renditions;
    @Autowired
    private PreviewPolicyService policies;
    @Autowired
    private DocumentTypeRepository documentTypeRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StorageLocationRepository storageLocationRepository;
    @Autowired
    private ObjectStorageServiceFactory storage;
    @Autowired
    private DocumentVersionRepository versionRepository;
    @Autowired
    private WorkflowFixtures fx;
    @Autowired
    private RecycleBinService recycleBin;
    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ pages

    @Test
    void aPdfIsDrawnPageByPageOnTheServer_andOpeningItIsAuditedOnce_notEveryPage() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Contract", "contract.pdf", pdf(3));

        DocumentRenditionService.PageDeck deck = renditions.pages(doc.documentId(), null);

        assertThat(deck.pages()).isEqualTo(3);
        assertThat(deck.partial()).isFalse();
        assertThat(deck.watermarked()).isFalse();
        BufferedImage page = ImageIO.read(new ByteArrayInputStream(renditions.page(doc.documentId(), null, 2, 600)));
        assertThat(page.getWidth()).isEqualTo(600);
        renditions.page(doc.documentId(), null, 3, 600);
        assertThat(auditCount("DOCUMENT_VIEWED", doc.documentId())).isEqualTo(1);

        assertThatThrownBy(() -> renditions.page(doc.documentId(), null, 4, 600))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> renditions.page(doc.documentId(), null, 0, 600))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aPictureIsAOnePageDocument() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Scan", "scan.png", png(300, 200));

        DocumentRenditionService.PageDeck deck = renditions.pages(doc.documentId(), null);

        assertThat(deck.pages()).isEqualTo(1);
        assertThat(deck.width()).isEqualTo(300f);
    }

    @Test
    void aTypeWithNoPagePreview_isRefused_butStillHasItsDetails() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Mail", "note.eml", "From: a@b.invalid\r\nSubject: hi\r\n\r\nhello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> renditions.pages(doc.documentId(), null))
                .isInstanceOfSatisfying(FolderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PREVIEW_NOT_PAGES"));
        assertThat(documentService.getDocument(doc.documentId()).title()).isEqualTo("Mail");
    }

    // ------------------------------------------------------------------ thumbnails

    @Test
    void aThumbnailIsMadeOnce_keptEncrypted_andServedFromStorageAfterwards() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Contract", "contract.pdf", pdf(2));

        byte[] first = renditions.thumbnail(doc.documentId(), null).orElseThrow();

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(first));
        assertThat(thumb.getWidth()).isEqualTo(SlidePages.THUMBNAIL_WIDTH);
        DocumentVersion version = versionRepository.findById(doc.currentVersionId()).orElseThrow();
        assertThat(version.getThumbnailStatus()).isEqualTo("completed");
        assertThat(version.getThumbnailObjectKey()).isNotNull();
        // Kept like the original: encrypted at rest, so what storage holds is not the picture.
        assertThat(storedRaw(version, version.getThumbnailObjectKey())).isNotEqualTo(first);
        // A second request is the stored one, byte for byte - not drawn again.
        assertThat(renditions.thumbnail(doc.documentId(), null).orElseThrow()).isEqualTo(first);
    }

    @Test
    void aThumbnailThatWentMissingFromStorage_isMadeAgainOnDemand() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Contract", "contract.pdf", pdf(1));
        renditions.thumbnail(doc.documentId(), null).orElseThrow();
        DocumentVersion version = versionRepository.findById(doc.currentVersionId()).orElseThrow();
        StorageLocation location = storageLocationRepository.findById(version.getStorageLocationId()).orElseThrow();
        storage.resolve(location).delete(location, version.getThumbnailObjectKey());

        assertThat(renditions.thumbnail(doc.documentId(), null)).isPresent();
        assertThat(storedRaw(version, version.getThumbnailObjectKey())).isNotEmpty();
    }

    @Test
    void aFileThatCantBeDrawn_hasNoThumbnail_andNoFailureIsMadeUpForIt() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Mail", "note.eml", "From: a@b.invalid\r\nSubject: hi\r\n\r\nhello".getBytes(StandardCharsets.UTF_8));

        assertThat(renditions.thumbnail(doc.documentId(), null)).isEmpty();
        assertThat(failedIds()).doesNotContain(doc.documentId());
    }

    @Test
    void aNewVersionGetsItsOwnThumbnail_theOldOneKeepsItsOwn() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Drawing", "drawing.png", png(300, 200));
        byte[] before = renditions.thumbnail(doc.documentId(), null).orElseThrow();
        UUID firstVersion = doc.currentVersionId();

        UUID secondVersion = fx.newVersion(f.tenantId(), doc.documentId(), f.user());
        retarget(secondVersion, "drawing.png", png(300, 200, 0xFF112233));
        DocumentResponse updated = documentService.getDocument(doc.documentId());
        assertThat(updated.currentVersionId()).isNotEqualTo(firstVersion);

        byte[] after = renditions.thumbnail(doc.documentId(), null).orElseThrow();
        assertThat(after).isNotEqualTo(before);
        assertThat(renditions.thumbnail(doc.documentId(), firstVersion).orElseThrow()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ access

    @Test
    void anotherOrganisationCantSeeAnotherOnesPagesOrThumbnails() throws Exception {
        Fixture owner = fixture();
        DocumentResponse doc = upload(owner, "Contract", "contract.pdf", pdf(1));
        renditions.thumbnail(doc.documentId(), null).orElseThrow();

        Fixture stranger = fixture();
        TenantContext.set(stranger.tenantId(), stranger.userId());

        assertThatThrownBy(() -> renditions.thumbnail(doc.documentId(), null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> renditions.pages(doc.documentId(), null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> renditions.page(doc.documentId(), null, 1, 600)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ the preview policy

    @Test
    void anOrganisationWithNoPolicy_allowsEverything() {
        Fixture f = fixture();

        assertThat(policies.mine()).isEqualTo(PreviewPolicyService.DEFAULT);
        assertThat(policies.mine().unrestricted()).isTrue();
    }

    @Test
    void thePolicyCanBeChanged_isAudited_andRefusesNonsense() {
        Fixture f = fixture();

        PreviewPolicyService.Policy saved = policies.save(new PreviewPolicyService.Policy("watermark", "block", "block"));

        assertThat(policies.mine()).isEqualTo(saved);
        assertThat(saved.unrestricted()).isFalse();
        assertThat(saved.watermarked()).isTrue();
        assertThat(auditCount("PREVIEW_POLICY_CHANGED", f.tenantId())).isEqualTo(1);
        assertThatThrownBy(() -> policies.save(new PreviewPolicyService.Policy("sometimes", "allow", "allow")))
                .isInstanceOfSatisfying(FolderException.class, e -> assertThat(e.getCode()).isEqualTo("PREVIEW_POLICY_INVALID"));
        // Copying text can be allowed or blocked - there is nothing to watermark.
        assertThatThrownBy(() -> policies.save(new PreviewPolicyService.Policy("allow", "allow", "watermark")))
                .isInstanceOf(FolderException.class);
        assertThat(policies.mine()).isEqualTo(saved);
    }

    @Test
    void aWatermarkPolicy_burnsTheViewerIntoEveryPage_andTellsTheViewerSo() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Contract", "contract.pdf", pdf(1));
        byte[] plain = renditions.page(doc.documentId(), null, 1, 600);

        policies.save(new PreviewPolicyService.Policy("watermark", "allow", "allow"));

        assertThat(renditions.pages(doc.documentId(), null).watermarked()).isTrue();
        assertThat(renditions.page(doc.documentId(), null, 1, 600)).isNotEqualTo(plain);
    }

    @Test
    void downloadingFromAPreview_followsThePolicy() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Contract", "contract.pdf", pdf(2));

        // allow: nothing special - the ordinary download runs.
        assertThat(renditions.downloadFromPreview(doc.documentId(), null)).isEmpty();

        policies.save(new PreviewPolicyService.Policy("allow", "block", "allow"));
        assertThatThrownBy(() -> renditions.downloadFromPreview(doc.documentId(), null))
                .isInstanceOfSatisfying(FolderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PREVIEW_DOWNLOAD_BLOCKED"));

        policies.save(new PreviewPolicyService.Policy("allow", "watermark", "allow"));
        DownloadedDocument copy = renditions.downloadFromPreview(doc.documentId(), null).orElseThrow();
        byte[] bytes;
        try (var in = copy.content()) {
            bytes = in.readAllBytes();
        }
        assertThat(copy.filename()).isEqualTo("contract (watermarked).pdf");
        assertThat(copy.contentType()).isEqualTo("application/pdf");
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            // Flattened: the original text can't be copied out of it.
            assertThat(new PDFTextStripper().getText(pdf).trim()).isEmpty();
        }
        assertThat(auditCount("DOCUMENT_DOWNLOADED", doc.documentId())).isEqualTo(1);
    }

    @Test
    void aFileThatCantCarryAWatermark_isNeverDownloadedPlainFromAPreview() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Mail", "note.eml", "From: a@b.invalid\r\nSubject: hi\r\n\r\nhello".getBytes(StandardCharsets.UTF_8));
        policies.save(new PreviewPolicyService.Policy("allow", "watermark", "allow"));

        assertThatThrownBy(() -> renditions.downloadFromPreview(doc.documentId(), null))
                .isInstanceOfSatisfying(FolderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PREVIEW_DOWNLOAD_BLOCKED"));
    }

    // ------------------------------------------------------------------ failures for the administrator

    @Test
    void aPreviewThatCantBeMade_isRecordedForTheAdministrator_withoutHidingTheDocument() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Broken", "broken.pdf", "%PDF-1.4 this is not really a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> renditions.pages(doc.documentId(), null))
                .isInstanceOfSatisfying(FolderException.class, e -> assertThat(e.getCode()).isEqualTo("PREVIEW_FAILED"));

        PageResponse<DocumentRenditionService.FailureView> failures = renditions.failures(PageRequest.of(0, 50));
        assertThat(failures.content()).anySatisfy(failure -> {
            assertThat(failure.documentId()).isEqualTo(doc.documentId());
            assertThat(failure.title()).isEqualTo("Broken");
            assertThat(failure.fileName()).isEqualTo("broken.pdf");
            assertThat(failure.reason()).isNotBlank();
            assertThat(failure.failedAt()).isNotNull();
        });
        assertThat(auditCount("PREVIEW_RENDITION_FAILED", doc.documentId())).isGreaterThanOrEqualTo(1);
        // The metadata is unaffected (DPRM-FR-006).
        assertThat(documentService.getDocument(doc.documentId()).title()).isEqualTo("Broken");
        // And the list is per organisation.
        Fixture other = fixture();
        TenantContext.set(other.tenantId(), other.userId());
        assertThat(failedIds()).doesNotContain(doc.documentId());
    }

    @Test
    void retryingAFailure_ofAVersionThatIsntThere_isNotFound() {
        Fixture f = fixture();

        assertThatThrownBy(() -> renditions.retry(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ purge

    @Test
    void purgingTheVersionsFiles_removesItsThumbnailToo() throws Exception {
        Fixture f = fixture();
        DocumentResponse doc = upload(f, "Contract", "contract.pdf", pdf(1));
        renditions.thumbnail(doc.documentId(), null).orElseThrow();
        DocumentVersion version = versionRepository.findById(doc.currentVersionId()).orElseThrow();
        String key = version.getThumbnailObjectKey();
        StorageLocation location = storageLocationRepository.findById(version.getStorageLocationId()).orElseThrow();
        assertThat(storedRaw(version, key)).isNotEmpty();

        recycleBin.destroyFiles(version);

        assertThatThrownBy(() -> storage.resolve(location).download(location, key))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(UUID tenantId, UUID userId, UUID documentTypeId, User user) {
    }

    private Fixture fixture() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Rendition test tenant " + UUID.randomUUID())
                .domain("rendition-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@rendition-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Viewer")
                .displayName("Test Viewer")
                .status(User.UserStatus.active)
                .build());
        storageLocationRepository.save(StorageLocation.builder()
                .tenantId(tenant.getTenantId())
                .name("rendition-filesystem-" + UUID.randomUUID())
                .provider(StorageProvider.FILE_SYSTEM)
                .basePath(tempDir.resolve(tenant.getTenantId().toString()).toString())
                .status(StorageLocation.Status.active)
                .build());
        for (StoragePurpose purpose : new StoragePurpose[]{StoragePurpose.staging, StoragePurpose.quarantine}) {
            storageLocationRepository.save(StorageLocation.builder()
                    .tenantId(tenant.getTenantId())
                    .name("rendition-" + purpose + "-" + UUID.randomUUID())
                    .provider(StorageProvider.FILE_SYSTEM)
                    .purpose(purpose)
                    .basePath(tempDir.resolve(tenant.getTenantId() + "-" + purpose).toString())
                    .status(StorageLocation.Status.active)
                    .build());
        }
        UUID typeId = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenant.getTenantId())
                .typeCode("RENDITION_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .typeName("Rendition test type")
                .build()).getDocumentTypeId();
        TenantContext.set(tenant.getTenantId(), user.getUserId(), "Test Viewer", keycloakId);
        return new Fixture(tenant.getTenantId(), user.getUserId(), typeId, user);
    }

    /** A document with one clean version holding {@code content}, made as the promoter makes it (no scan gate). */
    private DocumentResponse upload(Fixture f, String title, String filename, byte[] content) {
        UUID documentId = fx.document(f.tenantId(), f.documentTypeId(), f.user(), title);
        UUID versionId = jdbc.queryForObject("select current_version_id from documents where document_id = ?",
                UUID.class, documentId);
        retarget(versionId, filename, content);
        return documentService.getDocument(documentId);
    }

    /** Makes a version the file {@code filename} holding {@code content}, written to storage the way it reads it back. */
    private void retarget(UUID versionId, String filename, byte[] content) {
        jdbc.update("update document_versions set original_filename = ?, size_bytes = ? where version_id = ?",
                filename, content.length, versionId);
        fx.putVersionContent(versionId, content);
    }

    private java.util.List<UUID> failedIds() {
        return renditions.failures(PageRequest.of(0, 100)).content().stream()
                .map(DocumentRenditionService.FailureView::documentId).toList();
    }

    private byte[] storedRaw(DocumentVersion version, String key) throws IOException {
        StorageLocation location = storageLocationRepository.findById(version.getStorageLocationId()).orElseThrow();
        // Straight from the file system, below the encrypting layer (what is really on disk).
        try (var in = storage.resolveUnencrypted(location).download(location, key).content()) {
            return in.readAllBytes();
        }
    }

    private int auditCount(String action, UUID objectId) {
        Integer count = jdbc.queryForObject("select count(*) from audit_events where action = ? and object_id = ?",
                Integer.class, action, objectId);
        return count == null ? 0 : count;
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                    content.newLineAtOffset(72, 700);
                    content.showText("Secret page " + (i + 1));
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        return png(width, height, 0xFFCCDDEE);
    }

    private static byte[] png(int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
