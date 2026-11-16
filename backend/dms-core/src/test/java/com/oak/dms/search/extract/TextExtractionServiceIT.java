package com.oak.dms.search.extract;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.index.SearchSourceRepository;
import com.oak.dms.search.index.SearchSourceRepository.VersionRow;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the index gets its text, source by source (D4), against real storage with encryption at rest: plain text, the
 * preview PDF, the searchable PDF and OCR text of scanned files, and every unreadable case (password-protected,
 * corrupt, unsupported, too large, no text yet). Also proves the text is never logged, never spilled to a temp file, and
 * only ever cached through the encrypting store.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class TextExtractionServiceIT {

    private static final String MARKER = "ZEBRAMARKER";

    /**
     * Switches off the background thumbnail/preview worker (a package-private bean, so replaced by name): it would
     * race these tests by rewriting rendition status and keys on versions whose bytes are placeholders.
     */
    @MockitoBean(name = "renditionGenerationListener")
    private Object backgroundRenditions;

    @Autowired
    private TextExtractionService extraction;
    @Autowired
    private SearchSourceRepository source;
    @Autowired
    private SearchProperties props;
    @Autowired
    private WorkflowFixtures fx;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DocumentVersionRepository versions;
    @Autowired
    private StorageLocationRepository locations;
    @Autowired
    private StorageLocationResolver resolver;
    @Autowired
    private ObjectStorageServiceFactory storage;

    private UUID tenant;
    private User owner;
    private UUID type;
    private ListAppender<ILoggingEvent> logs;
    private long originalMaxBytes;

    @BeforeEach
    void setUp() {
        tenant = fx.tenant();
        owner = fx.user(tenant, "Extractor");
        type = fx.documentType(tenant, "Memo");
        logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger("com.oak.dms");
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
        originalMaxBytes = props.getExtraction().getMaxFileBytes();
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("com.oak.dms")).detachAppender(logs);
        props.getExtraction().setMaxFileBytes(originalMaxBytes);
    }

    // ------------------------------------------------------------------ the four sources

    @Test
    void plainTextIsReadDirectlyFromTheOriginal() {
        VersionRow v = version("notes.txt", "text/plain", (MARKER + " quarterly notes\nsecond line").getBytes(StandardCharsets.UTF_8));
        ExtractedText t = extraction.extract(v);
        assertThat(t.status()).isEqualTo(ExtractedText.Status.READY);
        assertThat(t.source()).isEqualTo("original");
        assertThat(t.text()).contains(MARKER + " quarterly notes", "second line");
    }

    @Test
    void anOfficeFileIsReadFromItsPreviewPdf() throws Exception {
        VersionRow v = version("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[]{1, 2, 3});
        withPreviewPdf(v, pdf(MARKER + " budget forecast"));
        ExtractedText t = extraction.extract(reload(v));
        assertThat(t.status()).isEqualTo(ExtractedText.Status.READY);
        assertThat(t.source()).isEqualTo("preview_pdf");
        assertThat(t.text()).contains(MARKER + " budget forecast");
    }

    @Test
    void aScannedFileUsesItsSearchablePdfBeforeAnythingElse() throws Exception {
        VersionRow v = version("scan.png", "image/png", new byte[]{1, 2, 3});
        withSearchablePdf(v, pdf(MARKER + " from the searchable pdf"));
        withOcrJson(v, "{\"text\":\"" + MARKER + " from ocr json\",\"pages\":[]}");
        ExtractedText t = extraction.extract(reload(v));
        assertThat(t.source()).isEqualTo("searchable_pdf");
        assertThat(t.text()).contains("from the searchable pdf");
    }

    @Test
    void ocrTextIsReadFromTheOcrOutputWithoutLoadingItsPages() throws Exception {
        VersionRow v = version("scan.png", "image/png", new byte[]{1, 2, 3});
        // pages/blocks come BEFORE the text field and must be skipped, not loaded
        withOcrJson(v, "{\"pages\":[{\"text\":\"page text\",\"blocks\":[{\"text\":\"b\",\"bbox\":[1,2,3,4]}]}],"
                + "\"text\":\"" + MARKER + " scanned contract\",\"language\":\"en\"}");
        ExtractedText t = extraction.extract(reload(v));
        assertThat(t.status()).isEqualTo(ExtractedText.Status.READY);
        assertThat(t.source()).isEqualTo("ocr");
        assertThat(t.text()).isEqualTo(MARKER + " scanned contract");
    }

    // ------------------------------------------------------------------ unreadable and not-yet cases

    @Test
    void anImageOnlyPdfIsPendingWhileOcrIsComing_andUnavailableWhenNoOcrWillCome() throws Exception {
        VersionRow v = version("scan.pdf", "application/pdf", new byte[]{1});
        withPreviewPdf(v, blankPdf());
        jdbc.update("update document_versions set ocr_status = 'processing' where version_id = ?", v.versionId());
        assertThat(extraction.extract(reload(v)).status()).isEqualTo(ExtractedText.Status.PENDING);

        jdbc.update("update document_versions set ocr_status = 'not_required' where version_id = ?", v.versionId());
        ExtractedText t = extraction.extract(reload(v));
        assertThat(t.status()).isEqualTo(ExtractedText.Status.UNAVAILABLE);
        assertThat(t.reason()).isEqualTo("no_text");
    }

    @Test
    void aPasswordProtectedPdfIsUnavailableAndDoesNotThrow() throws Exception {
        VersionRow v = version("secret.pdf", "application/pdf", new byte[]{1});
        withPreviewPdf(v, protectedPdf(MARKER + " hidden", "user-pass", "owner-pass", true));
        ExtractedText t = extraction.extract(reload(v));
        assertThat(t.status()).isEqualTo(ExtractedText.Status.UNAVAILABLE);
        assertThat(t.reason()).isEqualTo("password_protected");
    }

    @Test
    void anOwnerRestrictedPdfThatForbidsExtractionIsUnavailable() throws Exception {
        VersionRow v = version("locked.pdf", "application/pdf", new byte[]{1});
        withPreviewPdf(v, protectedPdf(MARKER + " no extraction", "", "owner-pass", false));
        ExtractedText t = extraction.extract(reload(v));
        assertThat(t.status()).isEqualTo(ExtractedText.Status.UNAVAILABLE);
        assertThat(t.reason()).isEqualTo("protected");
    }

    @Test
    void aCorruptPdfIsUnavailableAndDoesNotBlockOthers() throws Exception {
        VersionRow bad = version("broken.pdf", "application/pdf", new byte[]{1});
        withPreviewPdf(bad, "%PDF-1.7 this is not really a pdf".getBytes(StandardCharsets.UTF_8));
        VersionRow good = version("fine.txt", "text/plain", (MARKER + " fine").getBytes(StandardCharsets.UTF_8));
        ExtractedText t = extraction.extract(reload(bad));
        assertThat(t.status()).isEqualTo(ExtractedText.Status.UNAVAILABLE);
        assertThat(t.reason()).isEqualTo("corrupt");
        assertThat(extraction.extract(good).status()).isEqualTo(ExtractedText.Status.READY);
    }

    @Test
    void anUnsupportedFileWithNoRenditionAndNoOcrIsUnavailable() {
        VersionRow v = version("model.dwg", "application/acad", new byte[]{1, 2});
        jdbc.update("update document_versions set rendition_status = 'unsupported', ocr_status = 'not_required' where version_id = ?",
                v.versionId());
        ExtractedText t = extraction.extract(reload(v));
        assertThat(t.status()).isEqualTo(ExtractedText.Status.UNAVAILABLE);
        assertThat(t.reason()).isEqualTo("unsupported");
    }

    @Test
    void aFileOverTheLimitIsUnavailableAsTooLarge() {
        props.getExtraction().setMaxFileBytes(64);
        VersionRow v = version("big.txt", "text/plain", ("x".repeat(500)).getBytes(StandardCharsets.UTF_8));
        ExtractedText t = extraction.extract(v);
        assertThat(t.status()).isEqualTo(ExtractedText.Status.UNAVAILABLE);
        assertThat(t.reason()).isEqualTo("too_large");
    }

    @Test
    void aPendingRenditionIsPending() {
        VersionRow v = version("slides.pptx", "application/vnd.ms-powerpoint", new byte[]{1});
        jdbc.update("update document_versions set rendition_status = 'processing' where version_id = ?", v.versionId());
        assertThat(extraction.extract(reload(v)).status()).isEqualTo(ExtractedText.Status.PENDING);
    }

    // ------------------------------------------------------------------ cache, encryption, logs, temp files

    @Test
    void theTextCacheIsWrittenThroughTheEncryptingStore_isReused_andIgnoredWhenTheInputsChange() throws Exception {
        VersionRow v = version("notes.txt", "text/plain", (MARKER + " cache me").getBytes(StandardCharsets.UTF_8));
        extraction.extract(v);
        StorageLocation loc = resolver.resolveForTenant(tenant, StoragePurpose.document);
        String key = TextArtifactStore.keyFor(v.tenantId(), v.documentId(), v.versionId());
        assertThat(storage.resolve(loc).exists(loc, key)).as("cached after the first extraction").isTrue();

        // at rest the cached text is not readable from the storage layer alone
        byte[] raw;
        try (var in = storage.resolveUnencrypted(loc).download(loc, key).content()) {
            raw = in.readAllBytes();
        }
        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain(MARKER);

        // change the stored original behind the cache's back: the cache answers while the inputs are unchanged...
        fx.putVersionContent(v.versionId(), (MARKER + " different").getBytes(StandardCharsets.UTF_8));
        assertThat(extraction.extract(v).text()).contains("cache me");
        // ...and is ignored once an input (here the size) changes
        jdbc.update("update document_versions set size_bytes = size_bytes + 5 where version_id = ?", v.versionId());
        assertThat(extraction.extract(reload(v)).text()).contains("different");
    }

    @Test
    void pendingResultsAreNotCached() {
        VersionRow v = version("slides.pptx", "application/vnd.ms-powerpoint", new byte[]{1});
        jdbc.update("update document_versions set rendition_status = 'pending' where version_id = ?", v.versionId());
        extraction.extract(reload(v));
        StorageLocation loc = resolver.resolveForTenant(tenant, StoragePurpose.document);
        assertThat(storage.resolve(loc).exists(loc, TextArtifactStore.keyFor(v.tenantId(), v.documentId(), v.versionId()))).isFalse();
    }

    @Test
    void documentTextNeverReachesTheLogs_evenWhenExtractionFails() throws Exception {
        VersionRow ok = version("a.txt", "text/plain", (MARKER + " visible only to the index").getBytes(StandardCharsets.UTF_8));
        VersionRow corrupt = version("b.pdf", "application/pdf", new byte[]{1});
        withPreviewPdf(corrupt, ("%PDF-1.7 " + MARKER + " garbage").getBytes(StandardCharsets.UTF_8));
        extraction.extract(ok);
        extraction.extract(reload(corrupt));
        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).noneMatch(m -> m.contains(MARKER));
        assertThat(logs.list.stream().filter(e -> e.getThrowableProxy() != null)
                .map(e -> e.getThrowableProxy().getMessage()).filter(m -> m != null)).noneMatch(m -> m.contains(MARKER));
    }

    @Test
    void extractingALargePdfCreatesNoTempFiles() throws Exception {
        VersionRow v = version("long.pdf", "application/pdf", new byte[]{1});
        withPreviewPdf(v, pdf("page one " + MARKER, 60));
        long before = pdfboxTempFiles();
        ExtractedText t = extraction.extract(reload(v));
        assertThat(t.status()).isEqualTo(ExtractedText.Status.READY);
        assertThat(pdfboxTempFiles()).as("PDFBox must not spill parsed content to temp files").isEqualTo(before);
    }

    // ------------------------------------------------------------------ helpers

    private VersionRow version(String fileName, String mime, byte[] content) {
        UUID documentId = fx.document(tenant, type, owner, "Doc " + UUID.randomUUID());
        UUID versionId = jdbc.queryForObject("select current_version_id from documents where document_id = ?", UUID.class, documentId);
        jdbc.update("update document_versions set original_filename = ?, mime_type = ?, size_bytes = ? where version_id = ?",
                fileName, mime, content.length, versionId);
        fx.putVersionContent(versionId, content);
        return reload(versionId);
    }

    private VersionRow reload(VersionRow v) {
        return reload(v.versionId());
    }

    private VersionRow reload(UUID versionId) {
        return source.version(versionId).orElseThrow();
    }

    private void withPreviewPdf(VersionRow v, byte[] pdf) {
        DocumentVersion version = versions.findById(v.versionId()).orElseThrow();
        StorageLocation loc = locations.findById(version.getStorageLocationId()).orElseThrow();
        String key = "rendition/" + v.versionId() + "/preview.pdf";
        storage.resolve(loc).upload(loc, key, new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
        jdbc.update("update document_versions set rendition_status = 'completed', rendition_object_key = ? where version_id = ?",
                key, v.versionId());
    }

    private void withSearchablePdf(VersionRow v, byte[] pdf) {
        StorageLocation loc = resolver.resolveForTenant(v.tenantId(), StoragePurpose.document);
        String key = "ocr/" + v.tenantId() + "/" + v.documentId() + "/" + v.versionId() + "/searchable.pdf";
        storage.resolve(loc).upload(loc, key, new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
        jdbc.update("insert into renditions (tenant_id, version_id, rendition_type, storage_object_key, status) values (?, ?, 'searchable_pdf', ?, 'completed')",
                v.tenantId(), v.versionId(), key);
    }

    private void withOcrJson(VersionRow v, String json) {
        StorageLocation loc = resolver.resolveForTenant(v.tenantId(), StoragePurpose.document);
        String key = "ocr/" + v.tenantId() + "/" + v.documentId() + "/" + v.versionId() + "/ocr.json";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        storage.resolve(loc).upload(loc, key, new ByteArrayInputStream(bytes), bytes.length, "application/json");
        jdbc.update("insert into ocr_outputs (tenant_id, version_id, extracted_text_ref, status) values (?, ?, ?, 'completed')",
                v.tenantId(), v.versionId(), key);
        jdbc.update("update document_versions set ocr_status = 'completed' where version_id = ?", v.versionId());
    }

    private static byte[] pdf(String text) throws IOException {
        return pdf(text, 1);
    }

    private static byte[] pdf(String text, int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(i == 1 ? text : "filler text on page " + i);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] blankPdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] protectedPdf(String text, String userPassword, String ownerPassword, boolean canExtract) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            AccessPermission permission = new AccessPermission();
            permission.setCanExtractContent(canExtract);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword, permission);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static long pdfboxTempFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().contains("pdfbox")).count();
        }
    }
}
