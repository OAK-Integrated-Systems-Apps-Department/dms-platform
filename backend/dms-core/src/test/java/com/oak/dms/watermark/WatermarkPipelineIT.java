package com.oak.dms.watermark;

import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.job.WatermarkOutputSweeper;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import com.oak.dms.watermark.service.WatermarkService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.oak.dms.security.keycloak.KeycloakUserService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole thing, through the HTTP API, with nothing mocked: ask for a
 * copy, poll until it is ready, download it, and check what actually came
 * back.
 *
 * <h2>The assertion that matters most</h2>
 * The ORIGINAL must be byte-identical afterwards. This feature's entire
 * premise is that it produces a copy and never touches the source, and that
 * is the one property no amount of reading the code proves - a format
 * service that wrote back through an open stream, or a storage key collision
 * between the output and the source, would both look fine until somebody
 * opened the original and found a watermark on it.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkPipelineIT extends WatermarkFixtures {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private WatermarkService watermarkService;
    @Autowired
    private WatermarkJobRepository jobRepository;
    @Autowired
    private WatermarkOutputSweeper sweeper;
    @Autowired
    private UserRepository users;

    /**
     * The only piece of infrastructure this test cannot reach. The worker
     * runs as the requester, and {@code AuthorizationService} looks a
     * background job's realm roles up live from Keycloak rather than
     * trusting a token - so with no Keycloak there is no job, and the
     * pipeline could not be tested end to end at all.
     * <p>
     * It answers "no elevated realm role", which is the HARDER case: every
     * access check then has to pass on the fine-grained permissions the
     * fixtures actually grant, instead of being waved through by
     * TENANT_ADMIN. {@code aKeycloakOutageIsRetryable} makes it throw.
     */
    @MockitoBean
    private KeycloakUserService keycloakUserService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        org.mockito.Mockito.when(keycloakUserService.getPlatformRealmRoles(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
    }

    /**
     * Guards a regression this test caught: the watermark executor beans
     * are {@code Executor}s, and Boot gives up its own
     * {@code applicationTaskExecutor} as soon as any {@code Executor} bean
     * exists. That silently took out {@code ExportJobWorker} and
     * {@code ThreatNotificationListener}, both of which inject it by name -
     * switching watermarking on broke bulk export. The fix is
     * {@code defaultCandidate = false} on both pools; this asserts it.
     */
    @Test
    void switchingWatermarkingOnLeavesBootsOwnTaskExecutorAlone() {
        assertThat(context.containsBean("applicationTaskExecutor"))
                .describedAs("bulk export and threat notifications inject applicationTaskExecutor by name")
                .isTrue();
        assertThat(context.getBean(com.oak.dms.importexport.export.ExportJobWorker.class)).isNotNull();
        assertThat(context.getBean(com.oak.dms.watermark.config.WatermarkExecutorConfig.JOB_EXECUTOR)).isNotNull();
        assertThat(context.getBean(com.oak.dms.watermark.config.WatermarkExecutorConfig.FORMAT_EXECUTOR)).isNotNull();
    }

    @Test
    void aPdfIsWatermarkedEndToEndAndTheOriginalIsUntouched() throws Exception {
        Fixture fixture = pdfFixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        byte[] originalBefore = storedBytes(fixture);
        String hashBefore = storedHash(fixture);

        byte[] marked = runToDownload(fixture, "application/pdf", "report-watermarked.pdf");

        // A real PDF, same page count, visibly different when rendered.
        try (PDDocument output = Loader.loadPDF(marked)) {
            assertThat(output.getNumberOfPages()).isEqualTo(1);
        }
        assertThat(marked).isNotEqualTo(originalBefore);
        assertThat(renderedDifference(originalBefore, marked))
                .describedAs("the page must actually look different")
                .isGreaterThan(1000);

        assertOriginalUntouched(fixture, originalBefore, hashBefore);
    }

    @Test
    void anImageIsWatermarkedEndToEndAndTheOriginalIsUntouched() throws Exception {
        Fixture fixture = imageFixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        byte[] originalBefore = storedBytes(fixture);
        String hashBefore = storedHash(fixture);

        byte[] marked = runToDownload(fixture, "image/png", "photo-watermarked.png");

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(marked));
        assertThat(output).isNotNull();
        assertThat(output.getWidth()).isEqualTo(400);
        assertThat(marked).isNotEqualTo(originalBefore);
        assertThat(changedPixels(ImageIO.read(new ByteArrayInputStream(originalBefore)), output))
                .describedAs("the picture must actually be marked")
                .isGreaterThan(500);

        assertOriginalUntouched(fixture, originalBefore, hashBefore);
    }

    @Test
    void aSuccessfulDownloadIsAudited_andARefusedOneIsNot() throws Exception {
        Fixture fixture = pdfFixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        UUID jobId = completedJob(fixture);

        // Refused BEFORE any successful download, so there is no earlier row
        // that could make the next assertion pass by accident.
        jdbc.update("update watermark_jobs set expires_at = now() - interval '1 hour' where job_id = ?", jobId);
        mockMvc.perform(get("/api/v1/watermark-jobs/" + jobId + "/result").with(as(fixture)))
                .andExpect(status().isGone());
        assertThat(auditCount("WATERMARK_RESULT_DOWNLOADED", jobId))
                .describedAs("a refused attempt must not record that a copy was taken")
                .isZero();

        jdbc.update("update watermark_jobs set expires_at = now() + interval '1 hour' where job_id = ?", jobId);
        mockMvc.perform(get("/api/v1/watermark-jobs/" + jobId + "/result").with(as(fixture)))
                .andExpect(status().isOk());

        assertThat(auditCount("WATERMARK_RESULT_DOWNLOADED", jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select module from audit_events where action = ? and object_id = ?",
                String.class, "WATERMARK_RESULT_DOWNLOADED", jobId)).isEqualTo("DW");
    }

    @Test
    void anUnfinishedDownloadIsAlsoNotAuditedAsATakenCopy() throws Exception {
        Fixture fixture = pdfFixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        WatermarkJobResponse job = watermarkService.apply(fixture.documentId(), customText("DRAFT"), null);
        jdbc.update("update watermark_jobs set status = 'pending', output_object_key = null where job_id = ?",
                job.jobId());

        mockMvc.perform(get("/api/v1/watermark-jobs/" + job.jobId() + "/result").with(as(fixture)))
                .andExpect(status().isBadRequest());

        assertThat(auditCount("WATERMARK_RESULT_DOWNLOADED", job.jobId())).isZero();
    }

    @Test
    void theWorkerAuditsCompletion() {
        Fixture fixture = pdfFixture();
        UUID jobId = completedJob(fixture);
        assertThat(auditCount("WATERMARK_COMPLETED", jobId)).isEqualTo(1);
    }

    @Test
    void theSweeperDeletesAnExpiredCopyButKeepsTheRecordOfIt() {
        Fixture fixture = pdfFixture();
        UUID jobId = completedJob(fixture);
        String objectKey = jdbc.queryForObject(
                "select output_object_key from watermark_jobs where job_id = ?", String.class, jobId);
        assertThat(objectKey).isNotNull();

        jdbc.update("update watermark_jobs set expires_at = now() - interval '1 hour' where job_id = ?", jobId);
        sweeper.sweep();

        var swept = jobRepository.findById(jobId).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(WatermarkJobStatus.expired);
        assertThat(swept.getOutputObjectKey()).isNull();
        assertThat(swept.getPurgedAt()).isNotNull();
        // The row survives as the record of what was produced - only the
        // file and the pointer to it go.
        assertThat(swept.getCompletedAt()).isNotNull();
        assertThat(objectGone(fixture, objectKey)).isTrue();
    }

    @Test
    void theSweeperAlsoRemovesCopiesOfADeletedDocument() {
        Fixture fixture = pdfFixture();
        UUID jobId = completedJob(fixture);
        jdbc.update("update documents set is_deleted = true, deleted_at = now() where document_id = ?",
                fixture.documentId());

        sweeper.sweep();

        assertThat(jobRepository.findById(jobId).orElseThrow().getPurgedAt()).isNotNull();
    }

    // ---------------------------------------------------------- helpers

    /** POST, poll until the job finishes, then download - all over HTTP. */
    private byte[] runToDownload(Fixture fixture, String expectedType, String expectedFilename) throws Exception {
        String location = mockMvc.perform(post("/api/v1/documents/" + fixture.documentId() + "/watermark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customText\":\"CONFIDENTIAL - {username}\"}")
                        .with(as(fixture)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        UUID jobId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        awaitCompletion(jobId);

        // The endpoint answers with a StreamingResponseBody, so the first
        // dispatch only starts the async request - its body is written on
        // the second. Reading the response here instead would hand back
        // zero bytes that still look like a 200.
        var started = mockMvc.perform(get("/api/v1/watermark-jobs/" + jobId + "/result").with(as(fixture)))
                .andExpect(request().asyncStarted())
                .andReturn();
        var response = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
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

    /** Runs a job to completion without going through HTTP, for the tests that only need its output. */
    private UUID completedJob(Fixture fixture) {
        WatermarkJobResponse job = watermarkService.apply(fixture.documentId(), customText("CONFIDENTIAL"), null);
        awaitCompletion(job.jobId());
        return job.jobId();
    }

    /**
     * The source object exactly as storage holds it - fetched through the
     * same decrypting path a download uses, so this compares the document,
     * not its ciphertext.
     */
    private byte[] storedBytes(Fixture fixture) {
        var row = jdbc.queryForMap("select storage_location_id, storage_object_key from document_versions "
                + "where version_id = ?", fixture.versionId());
        StorageLocation location = storageLocationRepository
                .findById((UUID) row.get("storage_location_id")).orElseThrow();
        try (var in = storage.resolve(location).download(location, (String) row.get("storage_object_key")).content()) {
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

    private boolean objectGone(Fixture fixture, String objectKey) {
        StorageLocation staging = storageLocationRepository
                .findFirstByTenantIdAndPurposeAndStatusAndDeletedFalse(fixture.tenantId(),
                        com.oak.dms.storage.enums.StoragePurpose.staging, StorageLocation.Status.active)
                .orElseThrow();
        return !storage.resolve(staging).exists(staging, objectKey);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(Fixture fixture) {
        UUID keycloakId = users.findById(fixture.userId()).orElseThrow().getKeycloakId();
        return jwt().jwt(builder -> builder.subject(keycloakId.toString())
                .claim("tenant_id", fixture.tenantId().toString())).authorities();
    }

    private Fixture pdfFixture() {
        return fixtureWithContent("report.pdf", "application/pdf", realPdf());
    }

    private Fixture imageFixture() {
        return fixtureWithContent("photo.png", "image/png", realPng());
    }

    /** A PDF with actual page content, so a rendered comparison means something. */
    private static byte[] realPdf() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Original content that must survive watermarking");
                content.endText();
            }
            document.save(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static byte[] realPng() {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 400, 300);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(20, 20, 100, 60);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static long renderedDifference(byte[] before, byte[] after) {
        try (PDDocument a = Loader.loadPDF(before); PDDocument b = Loader.loadPDF(after)) {
            return changedPixels(new PDFRenderer(a).renderImageWithDPI(0, 72),
                    new PDFRenderer(b).renderImageWithDPI(0, 72));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long changedPixels(BufferedImage before, BufferedImage after) {
        long changed = 0;
        for (int y = 0; y < Math.min(before.getHeight(), after.getHeight()); y++) {
            for (int x = 0; x < Math.min(before.getWidth(), after.getWidth()); x++) {
                if ((before.getRGB(x, y) & 0xFFFFFF) != (after.getRGB(x, y) & 0xFFFFFF)) {
                    changed++;
                }
            }
        }
        return changed;
    }
}
