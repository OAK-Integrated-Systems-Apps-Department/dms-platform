package com.oak.dms.watermark;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.user.User;
import com.oak.dms.watermark.entity.TenantWatermarkSettings;
import com.oak.dms.watermark.entity.WatermarkTemplate;
import com.oak.dms.watermark.repository.TenantWatermarkSettingsRepository;
import com.oak.dms.watermark.repository.WatermarkTemplateRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DOC-02: the plain download used to read the whole file into a byte[] before asking whether the tenant's default
 * watermark applied at all, so every download - watermark off, which is the default - cost about twice the file's
 * size in heap, for as long as the client took to receive it. It now buffers only a file the watermark will
 * actually be drawn on, which the per-format size cap bounds; any other file streams straight from storage.
 * <p>
 * Real storage (filesystem, through the real encryption layer), real access checks, real watermark code - only the
 * scanner is skipped (see {@link WatermarkFixtures}). Allocation is measured per thread with the JVM's own
 * counters rather than by exhausting a heap, and asserted as a generous fraction of the file size (the measured
 * cost of a streamed 24 MB download is about 2 MB of fixed cipher buffers; the buffered one was about 51 MB), so
 * the checks hold across JVMs without depending on exact numbers.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class DocumentDownloadBufferingIT extends WatermarkFixtures {

    private static final int BIG = 24 * 1024 * 1024;

    @Autowired private DocumentService documentService;
    @Autowired private TenantWatermarkSettingsRepository settingsRepository;
    @Autowired private WatermarkTemplateRepository templateRepository;
    @Autowired private WebApplicationContext context;

    // ------------------------------------------------------------------ helpers

    private static byte[] bigPdfLookalike(int size) {
        byte[] content = new byte[size];
        Arrays.fill(content, (byte) 'A');
        byte[] head = "%PDF-1.4\n".getBytes();
        System.arraycopy(head, 0, content, 0, head.length);
        return content;
    }

    /** A real, openable one-page PDF the watermark code can draw on. */
    private static byte[] realPdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void defaultWatermarkOn(Fixture fixture, UUID templateId) {
        settingsRepository.save(TenantWatermarkSettings.builder().tenantId(fixture.tenantId())
                .downloadWatermarkEnabled(true).defaultTemplateId(templateId).build());
    }

    private static byte[] drain(DownloadedDocument downloaded) throws IOException {
        try (InputStream in = downloaded.content()) {
            return in.readAllBytes();
        }
    }

    private static String digestOfDrained(DownloadedDocument downloaded) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = downloaded.content()) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Bytes the calling thread allocated while {@code work} ran - read from the JVM, no heap exhaustion needed. */
    private static long allocatedBy(Callable<?> work) throws Exception {
        var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        work.call();
        return mx.getThreadAllocatedBytes(id) - before;
    }

    private static boolean isBuffered(DownloadedDocument downloaded) {
        return downloaded.content() instanceof ByteArrayInputStream;
    }

    // ----------------------------------------------------- download without a watermark

    @Test
    void aSmallDocument_downloadsUnchanged_withItsRecordedSizeTypeAndName() throws Exception {
        Fixture f = fixture();
        DownloadedDocument downloaded = documentService.downloadDocument(f.documentId());
        assertThat(downloaded.filename()).isEqualTo("report.pdf");
        assertThat(downloaded.contentType()).isEqualTo(PDF_MIME);
        assertThat(downloaded.sizeBytes()).isEqualTo(f.sizeBytes());
        assertThat(drain(downloaded)).isEqualTo(minimalPdf());
    }

    @Test
    void aLargeDocument_withNoWatermark_streamsWithoutBeingBuffered_andKeepsItsContent() throws Exception {
        byte[] original = bigPdfLookalike(BIG);
        Fixture f = fixtureWithContent("big.pdf", PDF_MIME, original);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));

        // Warm up (class loading, first-use allocations), then measure the real thing.
        drain(documentService.downloadDocument(f.documentId()));

        DownloadedDocument[] opened = new DownloadedDocument[1];
        String[] digest = new String[1];
        long allocated = allocatedBy(() -> {
            opened[0] = documentService.downloadDocument(f.documentId());
            digest[0] = digestOfDrained(opened[0]);
            return null;
        });

        assertThat(digest[0]).as("content matches the original").isEqualTo(expected);
        assertThat(isBuffered(opened[0])).as("handed over as the storage stream, not a byte[]").isFalse();
        assertThat(opened[0].sizeBytes()).isEqualTo(BIG);
        // The buffered version allocated ~2.1x the file; streamed it is a few MB of fixed buffers.
        assertThat(allocated).as("bytes allocated for a %d-byte download", BIG).isLessThan(BIG / 4);
    }

    @Test
    void severalConcurrentDownloads_dontAllocateInProportionToTheirCombinedSize() throws Exception {
        byte[] original = bigPdfLookalike(BIG);
        Fixture f = fixtureWithContent("big.pdf", PDF_MIME, original);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        drain(documentService.downloadDocument(f.documentId()));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<long[]>> runs = new ArrayList<>();
            List<String> digests = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                runs.add(pool.submit(() -> {
                    TenantContext.set(f.tenantId(), f.userId()); // thread-local: each request thread sets its own
                    try {
                        String[] d = new String[1];
                        long allocated = allocatedBy(() -> {
                            d[0] = digestOfDrained(documentService.downloadDocument(f.documentId()));
                            return null;
                        });
                        synchronized (digests) {
                            digests.add(d[0]);
                        }
                        return new long[]{allocated};
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            long total = 0;
            for (Future<long[]> run : runs) {
                total += run.get()[0];
            }
            assertThat(digests).hasSize(threads).allMatch(expected::equals);
            assertThat(total).as("bytes allocated by %d concurrent %d-byte downloads", threads, BIG)
                    .isLessThan((long) threads * BIG / 4);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------- download with the watermark on

    @Test
    void aPdfUnderTheSizeLimit_isStillWatermarked_whenTheTenantTurnedItOn() throws Exception {
        byte[] original = realPdf();
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, original);
        defaultWatermarkOn(f, SYSTEM_CONFIDENTIAL);

        DownloadedDocument downloaded = documentService.downloadDocument(f.documentId());
        byte[] result = drain(downloaded);

        assertThat(result).as("a watermark was drawn, so the bytes differ").isNotEqualTo(original);
        assertThat(downloaded.sizeBytes()).as("Content-Length is the watermarked length").isEqualTo(result.length);
        assertThat(downloaded.filename()).isEqualTo("memo.pdf");
        assertThat(downloaded.contentType()).isEqualTo(PDF_MIME);
        try (PDDocument pdf = Loader.loadPDF(result)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void aPdfOverTheSizeLimit_streamsUnchanged_evenWithTheWatermarkOn() throws Exception {
        long declared = 30L * 1024 * 1024; // over the 25 MB PDF cap - judged on the recorded size, not by reading
        Fixture f = fixture("huge.pdf", PDF_MIME, declared); // stored bytes are minimalPdf(), recorded size 30 MB
        defaultWatermarkOn(f, SYSTEM_CONFIDENTIAL);

        DownloadedDocument downloaded = documentService.downloadDocument(f.documentId());
        assertThat(isBuffered(downloaded)).isFalse();
        assertThat(downloaded.sizeBytes()).isEqualTo(declared);
        assertThat(drain(downloaded)).isEqualTo(minimalPdf());
    }

    @Test
    void anUnsupportedType_streamsUnchanged_evenWithTheWatermarkOn() throws Exception {
        byte[] original = "plain notes".getBytes();
        Fixture f = fixtureWithContent("notes.txt", "text/plain", original);
        defaultWatermarkOn(f, SYSTEM_CONFIDENTIAL);

        DownloadedDocument downloaded = documentService.downloadDocument(f.documentId());
        assertThat(isBuffered(downloaded)).isFalse();
        assertThat(drain(downloaded)).isEqualTo(original);
    }

    @Test
    void aRetiredWatermarkTemplate_streamsUnchanged() throws Exception {
        byte[] original = realPdf();
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, original);
        WatermarkTemplate retired = templateRepository.save(WatermarkTemplate.builder().tenantId(f.tenantId())
                .label("Retired").defaultText("OLD").active(false).build());
        defaultWatermarkOn(f, retired.getTemplateId());

        DownloadedDocument downloaded = documentService.downloadDocument(f.documentId());
        assertThat(isBuffered(downloaded)).isFalse();
        assertThat(drain(downloaded)).isEqualTo(original);
    }

    @Test
    void theWatermarkSwitchedOff_streamsUnchanged_whateverTemplateIsNamed() throws Exception {
        byte[] original = realPdf();
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, original);
        settingsRepository.save(TenantWatermarkSettings.builder().tenantId(f.tenantId())
                .downloadWatermarkEnabled(false).defaultTemplateId(SYSTEM_CONFIDENTIAL).build());

        DownloadedDocument downloaded = documentService.downloadDocument(f.documentId());
        assertThat(isBuffered(downloaded)).isFalse();
        assertThat(drain(downloaded)).isEqualTo(original);
    }

    // ------------------------------------------------------ access and storage failures

    @Test
    void aDocumentHiddenFromTheCaller_isNotFound() {
        Fixture f = fixture();
        User other = userRepository.save(User.builder().tenantId(f.tenantId()).keycloakId(UUID.randomUUID())
                .email(UUID.randomUUID() + "@doc02-it.invalid").username("other-" + UUID.randomUUID())
                .firstName("O").lastName("T").displayName("Other").status(User.UserStatus.active).build());
        jdbc.update("update documents set access_mode = 'restricted' where document_id = ?", f.documentId());
        TenantContext.set(f.tenantId(), other.getUserId());

        assertThatThrownBy(() -> documentService.downloadDocument(f.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anotherTenantsDocument_isNotFound() {
        Fixture a = fixture();
        Fixture b = fixture(); // sets the context to tenant B
        assertThatThrownBy(() -> documentService.downloadDocument(a.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(b.tenantId()).isNotEqualTo(a.tenantId());
    }

    @Test
    void aSoftDeletedDocument_isNotFound() {
        Fixture f = fixture();
        jdbc.update("update documents set is_deleted = true where document_id = ?", f.documentId());
        assertThatThrownBy(() -> documentService.downloadDocument(f.documentId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aMissingStorageObject_failsBeforeAnyBytesAreSent() {
        Fixture f = fixture();
        var row = jdbc.queryForMap("select storage_location_id, storage_object_key from document_versions "
                + "where version_id = ?", f.versionId());
        StorageLocation location = storageLocationRepository.findById((UUID) row.get("storage_location_id")).orElseThrow();
        storage.resolve(location).delete(location, (String) row.get("storage_object_key"));

        assertThatThrownBy(() -> documentService.downloadDocument(f.documentId()))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    // ----------------------------------------------------------- over HTTP: headers and bytes

    @Test
    void overHttp_theHeadersAndBytesAreWhatTheyWere() throws Exception {
        byte[] original = bigPdfLookalike(1024 * 1024);
        Fixture f = fixtureWithContent("annual report.pdf", PDF_MIME, original);
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        UUID keycloakId = userRepository.findById(f.userId()).orElseThrow().getKeycloakId();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        MvcResult started = mvc.perform(get("/api/v1/documents/" + f.documentId() + "/content")
                        .with(jwt().jwt(b -> b.subject(keycloakId.toString()).claim("tenant_id", f.tenantId().toString()))
                                .authorities()))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        MockHttpServletResponse response = done.getResponse();

        assertThat(response.getContentAsByteArray()).isEqualTo(original);
        assertThat(response.getHeader("Content-Type")).startsWith(PDF_MIME);
        assertThat(response.getHeader("Content-Length")).isEqualTo(String.valueOf(original.length));
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"annual report.pdf\"");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
