package com.oak.dms.document;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.preview.PreviewPolicyService;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * What the conversion does with a file by its size (DPRM alternate flow): converted whole under the limit, for its
 * first pages only past it, and not at all - flagged for an administrator's manual review - past the hard limit.
 * The converter is a throw-away local HTTP server that returns a three-page PDF and records what it was sent.
 */
class DocumentRenditionConversionTest {

    private static final long MAX = 1_000;
    private static final long PARTIAL_MAX = 5_000;

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer converter;
    private final VersionContent versionContent = mock(VersionContent.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final StorageLocationRepository locations = mock(StorageLocationRepository.class);
    private final ObjectStorageServiceFactory storage = mock(ObjectStorageServiceFactory.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID tenantId = UUID.randomUUID();
    private DocumentRenditionService service;

    @BeforeEach
    void setUp() throws Exception {
        byte[] pdf = threePagePdf();
        converter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        converter.createContext("/forms/libreoffice/convert", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, pdf.length);
            exchange.getResponseBody().write(pdf);
            exchange.close();
        });
        converter.start();

        when(locations.findByStorageLocationIdAndDeletedFalse(any())).thenReturn(java.util.Optional.of(
                StorageLocation.builder().storageLocationId(UUID.randomUUID()).build()));
        when(storage.resolve(any())).thenReturn(mock(com.oak.dms.storage.service.ObjectStorageService.class));
        when(versionContent.open(any(DocumentVersion.class), any(UUID.class))).thenAnswer(invocation ->
                new DownloadedDocument(new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)), 8,
                        "application/octet-stream", "big.docx"));

        service = new DocumentRenditionService(mock(DocumentRepository.class), versions,
                mock(DocumentAccessService.class), versionContent, locations, storage, mock(AuditService.class), jdbc,
                mock(PreviewPolicyService.class), mock(ApplicationEventPublisher.class), RestClient.builder(),
                "http://127.0.0.1:" + converter.getAddress().getPort(), Duration.ofSeconds(10), MAX, PARTIAL_MAX, 3, 10);
    }

    @AfterEach
    void tearDown() {
        converter.stop(0);
    }

    private DocumentVersion version(long size) {
        DocumentVersion version = DocumentVersion.builder()
                .versionId(UUID.randomUUID()).documentId(UUID.randomUUID()).tenantId(tenantId)
                .storageLocationId(UUID.randomUUID()).storageObjectKey("k").originalFilename("big.docx")
                .sizeBytes(size).build();
        when(versions.findById(version.getVersionId())).thenReturn(java.util.Optional.of(version));
        return version;
    }

    /** The arguments of every jdbc.update(sql, args...) whose SQL contains {@code fragment}. */
    private List<List<Object>> updates(String fragment) {
        List<List<Object>> found = new ArrayList<>();
        for (Invocation call : mockingDetails(jdbc).getInvocations()) {
            Object[] args = call.getArguments();
            if (call.getMethod().getName().equals("update") && args.length > 0 && args[0] instanceof String sql
                    && sql.contains(fragment)) {
                found.add(java.util.Arrays.asList(args).subList(1, args.length));
            }
        }
        return found;
    }

    @Test
    void aFileUnderTheLimit_isConvertedWhole() {
        DocumentVersion version = version(MAX - 1);

        service.generate(tenantId, version.getVersionId());

        assertThat(requests).isNotEmpty().allSatisfy(body -> assertThat(body).doesNotContain("nativePageRanges"));
        assertThat(updates("rendition_partial")).isNotEmpty()
                .allSatisfy(args -> assertThat(args).contains(false));
    }

    @Test
    void aFilePastTheLimit_isConvertedForItsFirstPagesOnly_andStoredAsPartial() {
        DocumentVersion version = version(MAX + 1);

        service.generate(tenantId, version.getVersionId());

        assertThat(requests).isNotEmpty().allSatisfy(body -> {
            assertThat(body).contains("nativePageRanges");
            assertThat(body).contains("1-3");
        });
        assertThat(updates("rendition_partial")).isNotEmpty()
                .allSatisfy(args -> assertThat(args).contains(true));
    }

    @Test
    void aFilePastTheHardLimit_isNotConverted_butFlaggedForManualReview() {
        DocumentVersion version = version(PARTIAL_MAX + 1);

        service.generate(tenantId, version.getVersionId());

        assertThat(requests).isEmpty();
        assertThat(updates("rendition_failure_reason")).isNotEmpty().anySatisfy(args -> {
            assertThat(args.get(0)).isEqualTo(Boolean.TRUE);
            assertThat(args.toString()).contains("needs manual review");
        });
    }

    @Test
    void aVersionThatIsGoneOrDeleted_isLeftAlone() {
        DocumentVersion gone = version(10);
        gone.setDeleted(true);

        service.generate(tenantId, gone.getVersionId());
        service.generate(tenantId, UUID.randomUUID());

        assertThat(requests).isEmpty();
        assertThat(updates("update document_versions")).isEmpty();
    }

    private static byte[] threePagePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < 3; i++) {
                document.addPage(new PDPage());
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
