package com.oak.dms.watermark;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.user.UserRepository;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import com.oak.dms.watermark.service.WatermarkService;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three Office formats through the whole thing: request over HTTP, the
 * real worker, the real storage and encryption layers, then download and
 * reopen what came back.
 *
 * <h2>What this adds over the unit tests</h2>
 * The unit tests prove the services put the right XML in the right part. They
 * say nothing about whether the pipeline AROUND them works for these types -
 * whether the content-type re-check accepts a .docx (all three Office formats
 * are zips with the same magic bytes and are told apart by extension), whether
 * the per-format size cap is read with the right key, whether the staged
 * output is stored and served with the right content type, and whether the
 * original survives. Each of those is a different way to ship a feature that
 * passes every unit test and fails on the first real document.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkOfficePipelineIT extends WatermarkFixtures {

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private WatermarkService watermarkService;
    @Autowired
    private WatermarkJobRepository jobRepository;
    @Autowired
    private UserRepository users;

    /** The worker runs as the requester, whose realm roles come from Keycloak. */
    @MockitoBean
    private KeycloakUserService keycloakUserService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.when(keycloakUserService.getPlatformRealmRoles(ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    @Test
    void aWordDocumentIsWatermarkedEndToEndAndStillOpens() throws Exception {
        Fixture fixture = fixtureWithContent("report.docx", OfficeFixtures.DOCX_MIME,
                OfficeFixtures.docxWithPopulatedDefaultHeader());
        grant(fixture, com.oak.dms.security.rbac.catalog.PermissionCodes.DOCM_DOCUMENTS_VIEW);
        byte[] before = storedBytes(fixture);
        String hashBefore = storedHash(fixture);

        byte[] marked = runToDownload(fixture, OfficeFixtures.DOCX_MIME, "report-watermarked.docx");

        assertThat(marked).isNotEqualTo(before);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(marked))) {
            assertThat(document.getHeaderList())
                    .describedAs("the copy must carry a header with the mark in it")
                    .isNotEmpty();
            assertThat(document.getHeaderList().stream()
                    .anyMatch(header -> header._getHdrFtr().xmlText().contains("anchor")))
                    .describedAs("and it must be an anchored drawing")
                    .isTrue();
        }
        assertOriginalUntouched(fixture, before, hashBefore);
    }

    @Test
    void aSpreadsheetIsWatermarkedEndToEndAndStillOpens() throws Exception {
        Fixture fixture = fixtureWithContent("book.xlsx", OfficeFixtures.XLSX_MIME,
                OfficeFixtures.richXlsx());
        grant(fixture, com.oak.dms.security.rbac.catalog.PermissionCodes.DOCM_DOCUMENTS_VIEW);
        byte[] before = storedBytes(fixture);
        String hashBefore = storedHash(fixture);

        byte[] marked = runToDownload(fixture, OfficeFixtures.XLSX_MIME, "book-watermarked.xlsx");

        assertThat(marked).isNotEqualTo(before);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(marked))) {
            assertThat(workbook.getNumberOfSheets())
                    .describedAs("no sheet may be lost")
                    .isEqualTo(4);
            assertThat(workbook.getSheetAt(0).getDrawingPatriarch().getShapes())
                    .describedAs("the first sheet must carry the mark")
                    .isNotEmpty();
        }
        assertOriginalUntouched(fixture, before, hashBefore);
    }

    @Test
    void aPresentationIsWatermarkedEndToEndAndStillOpens() throws Exception {
        Fixture fixture = fixtureWithContent("deck.pptx", OfficeFixtures.PPTX_MIME,
                OfficeFixtures.pptxWithHiddenMasterAndOpaqueCover());
        grant(fixture, com.oak.dms.security.rbac.catalog.PermissionCodes.DOCM_DOCUMENTS_VIEW);
        byte[] before = storedBytes(fixture);
        String hashBefore = storedHash(fixture);

        byte[] marked = runToDownload(fixture, OfficeFixtures.PPTX_MIME, "deck-watermarked.pptx");

        assertThat(marked).isNotEqualTo(before);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(marked))) {
            assertThat(show.getSlides()).hasSize(3);
            assertThat(show.getPictureData())
                    .describedAs("the mark must have been embedded")
                    .isNotEmpty();
        }
        assertOriginalUntouched(fixture, before, hashBefore);
    }

    @Test
    void eachOfficeFormatRecordsItsOwnAuditTrail() {
        Fixture fixture = fixtureWithContent("audited.docx", OfficeFixtures.DOCX_MIME,
                OfficeFixtures.plainDocx());

        WatermarkJobResponse job = watermarkService.apply(fixture.documentId(),
                customText("CONFIDENTIAL"), null);
        awaitCompletion(job.jobId());

        assertThat(auditCount("WATERMARK_REQUESTED", job.jobId()))
                .describedAs("the request itself is audited")
                .isEqualTo(1);
        assertThat(auditCount("WATERMARK_COMPLETED", job.jobId()))
                .describedAs("and so is the copy actually being produced")
                .isEqualTo(1);
        assertThat(auditCount("WATERMARK_FAILED", job.jobId())).isZero();
    }

    // ---------------------------------------------------------- helpers

    private byte[] runToDownload(Fixture fixture, String expectedType, String expectedFilename)
            throws Exception {
        String location = mockMvc.perform(post("/api/v1/documents/" + fixture.documentId() + "/watermark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customText\":\"CONFIDENTIAL - {username}\"}")
                        .with(as(fixture)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        UUID jobId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        awaitCompletion(jobId);

        // StreamingResponseBody: the first dispatch only starts the async
        // request, the body is written on the second.
        MvcResult started = mockMvc.perform(get("/api/v1/watermark-jobs/" + jobId + "/result")
                        .with(as(fixture)))
                .andExpect(request().asyncStarted())
                .andReturn();
        var response = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, expectedType))
                .andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains(expectedFilename);
        return response.getContentAsByteArray();
    }

    private void awaitCompletion(UUID jobId) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            var job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == WatermarkJobStatus.completed) {
                return;
            }
            if (job.getStatus() == WatermarkJobStatus.failed) {
                throw new AssertionError("Watermark job failed: " + job.getFailureCode()
                        + " - " + job.getFailureReason());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Watermark job " + jobId + " did not finish within " + PATIENCE);
    }

    private byte[] storedBytes(Fixture fixture) {
        var row = jdbc.queryForMap("select storage_location_id, storage_object_key from document_versions "
                + "where version_id = ?", fixture.versionId());
        StorageLocation location = storageLocationRepository
                .findById((UUID) row.get("storage_location_id")).orElseThrow();
        try (var in = storage.resolve(location)
                .download(location, (String) row.get("storage_object_key")).content()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String storedHash(Fixture fixture) {
        return jdbc.queryForObject("select file_hash from document_versions where version_id = ?",
                String.class, fixture.versionId());
    }

    private void assertOriginalUntouched(Fixture fixture, byte[] before, String hashBefore) {
        assertThat(storedBytes(fixture))
                .describedAs("the original's stored bytes must be untouched")
                .isEqualTo(before);
        assertThat(storedHash(fixture)).isEqualTo(hashBefore);
        assertThat(sha256(storedBytes(fixture)))
                .describedAs("and must still hash to what the version record says")
                .isEqualTo(hashBefore);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(Fixture fixture) {
        UUID keycloakId = users.findById(fixture.userId()).orElseThrow().getKeycloakId();
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .jwt().jwt(builder -> builder.subject(keycloakId.toString())
                        .claim("tenant_id", fixture.tenantId().toString()))
                .authorities();
    }
}
