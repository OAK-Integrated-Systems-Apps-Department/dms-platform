package com.oak.dms.watermark;

import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.watermark.entity.TenantWatermarkSettings;
import com.oak.dms.watermark.entity.WatermarkTemplate;
import com.oak.dms.watermark.repository.TenantWatermarkSettingsRepository;
import com.oak.dms.watermark.repository.WatermarkTemplateRepository;
import com.oak.dms.watermark.service.PlaceholderResolver;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/**
 * DOC-16 against real PostgreSQL, real file storage behind the encryption wrapper and the real controllers: the tenant's
 * default download watermark applies to the download of a named version - current or older - exactly as to the
 * document's plain download, with that version's own number; a signed file is served byte for byte and the audit event
 * says why; everything that was not meant to change did not.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class DefaultWatermarkVersionDownloadIT extends WatermarkFixtures {

    private static final int BIG = 24 * 1024 * 1024;
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired private WebApplicationContext context;
    @Autowired private TenantWatermarkSettingsRepository settingsRepository;
    @Autowired private WatermarkTemplateRepository templateRepository;
    @Autowired private DocumentVersionService versionService;
    @MockitoSpyBean private PlaceholderResolver placeholderResolver;

    private MockMvc mvc;

    record Res(int status, MockHttpServletResponse raw, byte[] body) {
        String h(String name) {
            return raw.getHeader(name);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * StreamingResponseBody is normally streamed from another thread while the request thread is still unwinding the
     * filter chain, and both write headers to the same MockHttpServletResponse - which is not thread-safe, and Spring
     * Security's header writer runs on each. Left alone, a download here fails now and then with a
     * ConcurrentModificationException (or the 500 it becomes), which is a defect of the test's response object, not of the
     * application. Streaming on the request thread, as the workflow ITs do, removes the race instead of retrying around it.
     */
    @BeforeEach
    void streamOnTheRequestThread() {
        context.getBean(RequestMappingHandlerAdapter.class).setTaskExecutor(new TaskExecutorAdapter(Runnable::run));
    }

    private Res get(Fixture as, String path) throws Exception {
        return fetch(as, path);
    }

    private Res fetch(Fixture as, String path) throws Exception {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }
        UUID keycloakId = userRepository.findById(as.userId()).orElseThrow().getKeycloakId();
        MvcResult started = mvc.perform(MockMvcRequestBuilders.get(path).with(jwt()
                .jwt(b -> b.subject(keycloakId.toString()).claim("tenant_id", as.tenantId().toString()))
                .authorities())).andReturn();
        MvcResult done = started.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(started)).andReturn() : started;
        MockHttpServletResponse r = done.getResponse();
        return new Res(r.getStatus(), r, r.getContentAsByteArray());
    }

    private static String content(Fixture f) {
        return "/api/v1/documents/" + f.documentId() + "/content";
    }

    private static String version(Fixture f, UUID versionId) {
        return "/api/v1/documents/" + f.documentId() + "/versions/" + versionId + "/content";
    }

    private void defaultOn(Fixture f, UUID templateId) {
        settingsRepository.save(TenantWatermarkSettings.builder().tenantId(f.tenantId())
                .downloadWatermarkEnabled(true).defaultTemplateId(templateId).build());
    }

    private void viewAndVersions(Fixture f) {
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        grant(f, PermissionCodes.DVC_VERSIONS_VIEW);
    }

    private static byte[] pdf(String marker, int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(marker + " page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Indirect objects in the file: a watermark adds its own graphics, so the count goes up. */
    private static int objects(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getDocument().getXrefTable().size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getNumberOfPages();
        }
    }

    private static String marker(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            String t = new PDFTextStripper().getText(d);
            return t.contains("V1 page") ? "V1" : t.contains("V2 page") ? "V2" : "other";
        }
    }

    private static boolean watermarked(byte[] result, byte[] original) {
        return !Arrays.equals(result, original) && objects(result) > objects(original);
    }

    /** A second version with its own bytes (the shared helper reuses version 1's object), made the current version. */
    private UUID addVersionWithContent(Fixture f, int number, byte[] content) {
        DocumentVersion first = versionRepository.findById(f.versionId()).orElseThrow();
        StorageLocation location = storageLocationRepository.findById(first.getStorageLocationId()).orElseThrow();
        UUID versionId = UUID.randomUUID();
        String key = f.tenantId() + "/" + f.documentId() + "/" + versionId;
        storage.resolve(location).upload(location, key, new ByteArrayInputStream(content), content.length, first.getMimeType());
        UUID staging = jdbc.queryForObject("select staging_location_id from scan_results where document_version_id = ?",
                UUID.class, f.versionId());
        UUID typeId = documentRepository.findById(f.documentId()).orElseThrow().getDocumentTypeId();
        UUID engineId = jdbc.queryForObject("select scan_engine_id from scan_engines where is_deleted = false "
                + "order by tenant_id nulls first limit 1", UUID.class);
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into scan_results (scan_result_id, tenant_id, scan_engine_id, staging_location_id,
                        original_filename, mime_type, status, scan_mode, attempt_count, uploaded_by,
                        ingestion_kind, created_reason, document_type_id, title, document_version_id,
                        file_hash, engine_name, completed_at)
                    values (?, ?, ?, ?, ?, ?, 'clean', 'pre_store_stream', 1, ?, 'new_document',
                        'initial_upload', ?, ?, ?, ?, 'test-harness', now())
                    """, UUID.randomUUID(), f.tenantId(), engineId, staging, first.getOriginalFilename(), first.getMimeType(),
                    f.userId(), typeId, "Watermark subject", versionId, sha256(content));
            versionRepository.save(DocumentVersion.builder().versionId(versionId).documentId(f.documentId())
                    .tenantId(f.tenantId()).versionNumber(number).majorVersion(number).minorVersion(0)
                    .storageLocationId(first.getStorageLocationId()).storageObjectKey(key)
                    .originalFilename(first.getOriginalFilename()).mimeType(first.getMimeType())
                    .sizeBytes(content.length).fileHash(sha256(content)).createdReason("replacement_upload")
                    .scanStatus(DocumentVersion.SCAN_STATUS_CLEAN).createdBy(f.userId()).build());
            var d = documentRepository.findById(f.documentId()).orElseThrow();
            d.setCurrentVersionId(versionId);
            d.setLatestVersionId(versionId);
            d.setUpdatedAt(OffsetDateTime.now());
            documentRepository.save(d);
        });
        return versionId;
    }

    /** The new_value JSON of every audit row of this action on this document (empty string where there is none). */
    private List<String> auditValues(String action, UUID documentId) {
        return jdbc.queryForList("select coalesce(new_value::text, '') from audit_events where action = ? and object_id = ?",
                String.class, action, documentId);
    }

    // ------------------------------------------------------------------ 1-3: the version route now applies the policy

    @Test
    void theCurrentVersion_isWatermarkedOnTheVersionRoute_likeThePlainDownload() throws Exception {
        byte[] original = pdf("V1", 1);
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, original);
        viewAndVersions(f);
        defaultOn(f, SYSTEM_CONFIDENTIAL);

        Res plain = get(f, content(f));
        Res byVersion = get(f, version(f, f.versionId()));

        assertThat(plain.status()).isEqualTo(200);
        assertThat(byVersion.status()).isEqualTo(200);
        assertThat(watermarked(plain.body(), original)).as("plain download").isTrue();
        assertThat(watermarked(byVersion.body(), original)).as("DOC-16: the version route is watermarked too").isTrue();
        assertThat(byVersion.h("Content-Length")).isEqualTo(String.valueOf(byVersion.body().length));
        assertThat(pages(byVersion.body())).isEqualTo(1);
        assertThat(marker(byVersion.body())).as("it is still this file").isEqualTo("V1");
        assertThat(byVersion.h("Content-Type")).startsWith("application/pdf");
        assertThat(byVersion.h("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void anOlderVersion_isWatermarked_withItsOwnContent_andTheCurrentOneToo_whichIsStillWhatThePlainDownloadServes() throws Exception {
        byte[] v1 = pdf("V1", 1);
        byte[] v2 = pdf("V2", 2);
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, v1);
        viewAndVersions(f);
        UUID v2Id = addVersionWithContent(f, 2, v2);
        defaultOn(f, SYSTEM_CONFIDENTIAL);

        Res older = get(f, version(f, f.versionId()));
        Res current = get(f, version(f, v2Id));
        Res plain = get(f, content(f));

        assertThat(watermarked(older.body(), v1)).as("older version status=%d len=%d origLen=%d equal=%b objs=%d origObjs=%d",
                older.status(), older.body().length, v1.length, Arrays.equals(older.body(), v1), objects(older.body()), objects(v1)).isTrue();
        assertThat(pages(older.body())).isEqualTo(1);
        assertThat(marker(older.body())).as("the older version's own content").isEqualTo("V1");
        assertThat(watermarked(current.body(), v2)).as("current version by id").isTrue();
        assertThat(pages(current.body())).isEqualTo(2);
        assertThat(marker(current.body())).isEqualTo("V2");
        assertThat(watermarked(plain.body(), v2)).isTrue();
        assertThat(marker(plain.body())).isEqualTo("V2");
    }

    @Test
    void theVersionPlaceholder_namesTheRequestedVersion_notTheCurrentOne() throws Exception {
        byte[] v1 = pdf("V1", 1);
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, v1);
        viewAndVersions(f);
        UUID v2Id = addVersionWithContent(f, 2, pdf("V2", 1));
        WatermarkTemplate template = templateRepository.save(WatermarkTemplate.builder().tenantId(f.tenantId())
                .label("Versioned").defaultText("VER{documentVersion}").active(true).build());
        defaultOn(f, template.getTemplateId());
        List<String> resolved = Collections.synchronizedList(new ArrayList<>());
        Mockito.doAnswer(call -> {
            String text = (String) call.callRealMethod();
            resolved.add(text);
            return text;
        }).when(placeholderResolver).resolve(Mockito.anyString(), Mockito.any());

        get(f, version(f, f.versionId()));
        get(f, version(f, v2Id));
        get(f, content(f));

        assertThat(resolved).as("older version, current version by id, plain download (the current version)")
                .containsExactly("VER1", "VER2", "VER2");
    }

    // ------------------------------------------------------------------ 4: streaming when the watermark does not apply (DOC-02)

    @Test
    void withTheWatermarkOff_aVersionDownload_streamsWithoutBuffering_andKeepsItsContent() throws Exception {
        byte[] original = new byte[BIG];
        Arrays.fill(original, (byte) 'A');
        System.arraycopy("%PDF-1.4\n".getBytes(), 0, original, 0, 9);
        Fixture f = fixtureWithContent("big.pdf", PDF_MIME, original);
        String expected = sha256(original);
        versionService.download(f.documentId(), f.versionId()).content().close();      // warm up

        DownloadedDocument[] opened = new DownloadedDocument[1];
        String[] digest = new String[1];
        var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        opened[0] = versionService.download(f.documentId(), f.versionId());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = opened[0].content()) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                md.update(buffer, 0, n);
            }
        }
        digest[0] = java.util.HexFormat.of().formatHex(md.digest());
        long allocated = mx.getThreadAllocatedBytes(id) - before;

        assertThat(digest[0]).isEqualTo(expected);
        assertThat(opened[0].content()).as("the storage stream, not a byte[]").isNotInstanceOf(ByteArrayInputStream.class);
        assertThat(opened[0].sizeBytes()).isEqualTo(BIG);
        assertThat(allocated).as("bytes allocated for a %d-byte download", BIG).isLessThan(BIG / 4);
    }

    @Test
    void withTheWatermarkOff_bothRoutesServeTheOriginal() throws Exception {
        byte[] original = pdf("V1", 1);
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, original);
        viewAndVersions(f);

        assertThat(get(f, content(f)).body()).isEqualTo(original);
        assertThat(get(f, version(f, f.versionId())).body()).isEqualTo(original);
    }

    // ------------------------------------------------------------------ 5-6: preview and the preview channel

    @Test
    void inlinePreviews_areNeverWatermarked_andThePreviewChannelFollowsTheSameFallbackOnBothRoutes() throws Exception {
        byte[] original = pdf("V1", 1);
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, original);
        viewAndVersions(f);
        defaultOn(f, SYSTEM_CONFIDENTIAL);

        assertThat(get(f, content(f) + "?inline=true").body()).as("inline /content").isEqualTo(original);
        assertThat(get(f, version(f, f.versionId()) + "?inline=true").body()).as("inline version").isEqualTo(original);
        Res plainChannel = get(f, content(f) + "?channel=preview");
        Res versionChannel = get(f, version(f, f.versionId()) + "?channel=preview");
        // With no preview policy the channel falls back to the plain download of that route; both now apply the default policy.
        assertThat(watermarked(plainChannel.body(), original)).isTrue();
        assertThat(watermarked(versionChannel.body(), original)).isTrue();
        assertThat(auditValues("DOCUMENT_VIEWED", f.documentId())).as("one DOCUMENT_VIEWED per inline preview").hasSize(2);
    }

    // ------------------------------------------------------------------ 7-9: fallbacks

    @Test
    void unsupportedTypes_retiredTemplates_andFilesOverTheCap_areServedAsStored_onTheVersionRoute() throws Exception {
        byte[] txt = "plain notes".getBytes(StandardCharsets.UTF_8);
        Fixture unsupported = fixtureWithContent("notes.txt", "text/plain", txt);
        viewAndVersions(unsupported);
        defaultOn(unsupported, SYSTEM_CONFIDENTIAL);
        assertThat(get(unsupported, version(unsupported, unsupported.versionId())).body()).isEqualTo(txt);

        byte[] original = pdf("V1", 1);
        Fixture retired = fixtureWithContent("memo.pdf", PDF_MIME, original);
        viewAndVersions(retired);
        WatermarkTemplate old = templateRepository.save(WatermarkTemplate.builder().tenantId(retired.tenantId())
                .label("Retired").defaultText("OLD").active(false).build());
        defaultOn(retired, old.getTemplateId());
        assertThat(get(retired, version(retired, retired.versionId())).body()).isEqualTo(original);

        Fixture huge = fixture("huge.pdf", PDF_MIME, 30L * 1024 * 1024);      // recorded size over the 25 MB PDF cap
        viewAndVersions(huge);
        defaultOn(huge, SYSTEM_CONFIDENTIAL);
        assertThat(get(huge, version(huge, huge.versionId())).body()).isEqualTo(minimalPdf());
    }

    // ------------------------------------------------------------------ 10-11: signed files, byte for byte

    /** The signature value is the SHA-256 of the signed byte ranges: a structural stand-in (no CMS library in this build). */
    private static byte[] signedPdf() throws Exception {
        byte[] base = pdf("SIGNED", 1);
        try (PDDocument doc = Loader.loadPDF(base); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDSignature sig = new PDSignature();
            sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            sig.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            sig.setName("Test Signer");
            sig.setSignDate(java.util.Calendar.getInstance());
            SignatureInterface digestOnly = in -> {
                try {
                    return MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            };
            doc.addSignature(sig, digestOnly);
            doc.saveIncremental(out);
            return out.toByteArray();
        }
    }

    private static boolean signatureStillMatches(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDSignature s = d.getSignatureDictionaries().get(0);
            return Arrays.equals(MessageDigest.getInstance("SHA-256").digest(s.getSignedContent(pdf)),
                    Arrays.copyOf(s.getContents(pdf), 32));
        }
    }

    private static byte[] signedDocx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String[] part : new String[][]{{"[Content_Types].xml", "<Types/>"}, {"word/document.xml", "<w:document/>"},
                    {"_xmlsignatures/sig1.xml", "<Signature/>"}}) {
                zip.putNextEntry(new ZipEntry(part[0]));
                zip.write(part[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void aSignedPdf_isServedByteForByte_onBothRoutes_andEachAuditEventNamesTheExemption() throws Exception {
        byte[] signed = signedPdf();
        assertThat(signatureStillMatches(signed)).isTrue();
        Fixture f = fixtureWithContent("contract.pdf", PDF_MIME, signed);
        viewAndVersions(f);
        defaultOn(f, SYSTEM_CONFIDENTIAL);

        Res plain = get(f, content(f));
        Res byVersion = get(f, version(f, f.versionId()));

        assertThat(plain.body()).isEqualTo(signed);
        assertThat(byVersion.body()).isEqualTo(signed);
        assertThat(byVersion.h("Content-Length")).isEqualTo(String.valueOf(signed.length));
        assertThat(signatureStillMatches(byVersion.body())).as("the signature still matches its signed ranges").isTrue();
        List<String> audits = auditValues("DOCUMENT_DOWNLOADED", f.documentId());
        assertThat(audits).as("exactly one DOCUMENT_DOWNLOADED per request - no duplicate event").hasSize(2);
        assertThat(audits).allSatisfy(v -> assertThat(v).contains("\"watermarkExemption\"").contains("SIGNED_CONTENT_EXEMPT"));
        assertThat(audits).anySatisfy(v -> assertThat(v).contains("versionId"));
    }

    @Test
    void aSignedOfficeFile_isServedByteForByte_andTheExemptionIsAudited() throws Exception {
        byte[] signed = signedDocx();
        Fixture f = fixtureWithContent("memo.docx", DOCX_MIME, signed);
        viewAndVersions(f);
        defaultOn(f, SYSTEM_CONFIDENTIAL);

        Res byVersion = get(f, version(f, f.versionId()));
        Res plain = get(f, content(f));

        assertThat(byVersion.body()).isEqualTo(signed);
        assertThat(plain.body()).isEqualTo(signed);
        assertThat(auditValues("DOCUMENT_DOWNLOADED", f.documentId())).hasSize(2)
                .allSatisfy(v -> assertThat(v).contains("SIGNED_CONTENT_EXEMPT"));
    }

    // ------------------------------------------------------------------ 12-14: authorization, audit, headers

    @Test
    void authorizationAndTenantIsolation_areUnchanged_andARefusedRequestWritesNoDownloadEvent() throws Exception {
        byte[] original = pdf("V1", 1);
        Fixture owner = fixtureWithContent("memo.pdf", PDF_MIME, original);
        viewAndVersions(owner);
        defaultOn(owner, SYSTEM_CONFIDENTIAL);
        Fixture viewOnly = fixtureWithContent("memo.pdf", PDF_MIME, original);
        grant(viewOnly, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        defaultOn(viewOnly, SYSTEM_CONFIDENTIAL);
        Fixture other = fixtureWithContent("memo.pdf", PDF_MIME, original);
        viewAndVersions(other);

        assertThat(get(viewOnly, content(viewOnly)).status()).isEqualTo(200);
        assertThat(get(viewOnly, version(viewOnly, viewOnly.versionId())).status()).as("no dvc.view-version-history.view").isEqualTo(403);
        assertThat(get(other, content(owner)).status()).as("another tenant").isEqualTo(404);
        assertThat(get(other, version(owner, owner.versionId())).status()).as("another tenant, version route").isEqualTo(404);
        assertThat(auditValues("DOCUMENT_DOWNLOADED", owner.documentId())).as("nothing was downloaded from the owner's document").isEmpty();
        assertThat(auditValues("DOCUMENT_DOWNLOADED", viewOnly.documentId())).as("only the permitted plain download").hasSize(1);
    }

    @Test
    void theDownloadAudit_isOneEventPerRequest_namingTheVersion_withNoExemptionForOrdinaryFiles() throws Exception {
        byte[] v1 = pdf("V1", 1);
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, v1);
        viewAndVersions(f);
        UUID v2Id = addVersionWithContent(f, 2, pdf("V2", 1));
        defaultOn(f, SYSTEM_CONFIDENTIAL);

        get(f, version(f, f.versionId()));
        get(f, version(f, v2Id));

        List<String> audits = auditValues("DOCUMENT_DOWNLOADED", f.documentId());
        assertThat(audits).hasSize(2);
        assertThat(audits).anySatisfy(v -> assertThat(v).contains(f.versionId().toString()));
        assertThat(audits).anySatisfy(v -> assertThat(v).contains(v2Id.toString()));
        assertThat(audits).noneSatisfy(v -> assertThat(v).contains("watermarkExemption"));
    }

    @Test
    void doc15_theContentDispositionHeaderIsTheSameOnBothRoutes_forAnInternationalFileName() throws Exception {
        byte[] original = pdf("V1", 1);
        Fixture f = fixtureWithContent("ගොනුව.pdf", PDF_MIME, original);
        viewAndVersions(f);
        defaultOn(f, SYSTEM_CONFIDENTIAL);

        Res plain = get(f, content(f));
        Res byVersion = get(f, version(f, f.versionId()));

        assertThat(byVersion.h("Content-Disposition")).startsWith("attachment; filename=\"document.pdf\"; filename*=UTF-8''%E0%B6%9C");
        assertThat(byVersion.h("Content-Disposition")).isEqualTo(plain.h("Content-Disposition"));
    }
}
