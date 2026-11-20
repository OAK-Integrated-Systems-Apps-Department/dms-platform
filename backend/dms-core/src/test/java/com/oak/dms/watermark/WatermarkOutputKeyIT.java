package com.oak.dms.watermark;

import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.user.UserRepository;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.job.WatermarkOutputSweeper;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import com.oak.dms.watermark.service.WatermarkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DOC-20 on real PostgreSQL and real storage: a watermark output is stored under an id-only key whatever the document's
 * original file name says, downloads and expires as before, and the sweeper never deletes through a stored key that is
 * not the job's own (an old key built from a hostile file name).
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkOutputKeyIT extends WatermarkFixtures {

    @Autowired private WebApplicationContext context;
    @Autowired private WatermarkService watermarkService;
    @Autowired private WatermarkJobRepository jobRepository;
    @Autowired private WatermarkOutputSweeper sweeper;
    @Autowired private UserRepository users;
    /** The worker looks the requester's realm roles up from Keycloak; there is none here, so "no elevated role". */
    @MockitoBean private KeycloakUserService keycloakUserService;

    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        org.mockito.Mockito.when(keycloakUserService.getPlatformRealmRoles(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        // Stream on the request thread: MockHttpServletResponse is not thread-safe (see DownloadStreamTransactionIT).
        context.getBean(RequestMappingHandlerAdapter.class).setTaskExecutor(new TaskExecutorAdapter(Runnable::run));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private UUID completedJob(Fixture f) {
        WatermarkJobResponse job = watermarkService.apply(f.documentId(), customText("CONFIDENTIAL"), null);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            var row = jobRepository.findById(job.jobId()).orElseThrow();
            if (row.getStatus() == WatermarkJobStatus.completed) {
                return job.jobId();
            }
            if (row.getStatus() == WatermarkJobStatus.failed) {
                throw new AssertionError("job failed: " + row.getFailureCode() + " - " + row.getFailureReason());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("job did not finish");
    }

    private StorageLocation staging(Fixture f) {
        return storageLocationRepository.findFirstByTenantIdAndPurposeAndStatusAndDeletedFalse(f.tenantId(),
                StoragePurpose.staging, StorageLocation.Status.active).orElseThrow();
    }

    private boolean exists(Fixture f, String key) {
        StorageLocation location = staging(f);
        return storage.resolve(location).exists(location, key);
    }

    private void put(Fixture f, String key, String content) {
        StorageLocation location = staging(f);
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.resolve(location).upload(location, key, new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
    }

    private String keyOf(UUID jobId) {
        return jdbc.queryForObject("select output_object_key from watermark_jobs where job_id = ?", String.class, jobId);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(Fixture f) {
        UUID keycloakId = users.findById(f.userId()).orElseThrow().getKeycloakId();
        return jwt().jwt(b -> b.subject(keycloakId.toString()).claim("tenant_id", f.tenantId().toString())).authorities();
    }

    private void expire(UUID jobId) {
        jdbc.update("update watermark_jobs set expires_at = now() - interval '1 hour' where job_id = ?", jobId);
    }

    @Test
    void aHostileOriginalFilename_isNeverInTheKey_andTheJobStillCompletesAndDownloads() throws Exception {
        Fixture f = fixtureWithContent("../../x.pdf", PDF_MIME, minimalPdf());
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);

        UUID jobId = completedJob(f);

        String key = keyOf(jobId);
        assertThat(key).isEqualTo(f.tenantId() + "/tmp/watermarks/" + jobId + "/output");
        assertThat(exists(f, key)).isTrue();
        assertThat(exists(f, f.tenantId() + "/tmp/x.pdf")).as("nothing written outside the job directory").isFalse();

        var started = mvc.perform(get("/api/v1/watermark-jobs/" + jobId + "/result").with(as(f)))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("x-watermarked.pdf").doesNotContain("..");
        assertThat(response.getContentAsByteArray()).startsWith("%PDF".getBytes());
        assertThat(jobRepository.findById(jobId).orElseThrow().getOutputSizeBytes())
                .isEqualTo((long) response.getContentAsByteArray().length);
    }

    @Test
    void anExpiredOutput_isSweptAndItsKeyCleared() {
        Fixture f = fixtureWithContent("report.pdf", PDF_MIME, minimalPdf());
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        UUID jobId = completedJob(f);
        String key = keyOf(jobId);
        assertThat(exists(f, key)).isTrue();

        expire(jobId);
        sweeper.sweep();

        assertThat(exists(f, key)).isFalse();
        assertThat(keyOf(jobId)).isNull();
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(WatermarkJobStatus.expired);
    }

    @Test
    void aLegitimateLegacyKey_isStillSwept() {
        Fixture f = fixtureWithContent("report.pdf", PDF_MIME, minimalPdf());
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        UUID jobId = completedJob(f);
        String legacy = f.tenantId() + "/tmp/watermarks/" + jobId + "/report.pdf";
        put(f, legacy, "LEGACY OUTPUT");
        jdbc.update("update watermark_jobs set output_object_key = ? where job_id = ?", legacy, jobId);
        expire(jobId);

        sweeper.sweep();

        assertThat(exists(f, legacy)).as("the old-style output is deleted").isFalse();
        assertThat(keyOf(jobId)).isNull();
    }

    @Test
    void aLegacyKeyThatLeavesTheJobDirectory_isNeverDeleted_andStaysOnTheRowForReview() {
        Fixture f = fixtureWithContent("report.pdf", PDF_MIME, minimalPdf());
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        UUID jobId = completedJob(f);
        UUID otherJob = UUID.randomUUID();
        String victim = f.tenantId() + "/tmp/watermarks/" + otherJob + "/their-report.pdf";
        put(f, victim, "SOMEONE ELSE'S OUTPUT");
        // Matches the job's own prefix, then climbs out of it into a sibling job's directory.
        String hostile = f.tenantId() + "/tmp/watermarks/" + jobId + "/../" + otherJob + "/their-report.pdf";
        jdbc.update("update watermark_jobs set output_object_key = ? where job_id = ?", hostile, jobId);
        expire(jobId);

        sweeper.sweep();

        assertThat(exists(f, victim)).as("the other job's object is untouched").isTrue();
        assertThat(keyOf(jobId)).as("kept for investigation").isEqualTo(hostile);
        var row = jobRepository.findById(jobId).orElseThrow();
        assertThat(row.getStatus()).as("but the copy is no longer offered").isEqualTo(WatermarkJobStatus.expired);
        assertThat(row.getPurgedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ the read path (download)

    private org.springframework.mock.web.MockHttpServletResponse result(Fixture f, UUID jobId) throws Exception {
        var started = mvc.perform(get("/api/v1/watermark-jobs/" + jobId + "/result").with(as(f))).andReturn();
        var done = started.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(started)).andReturn() : started;
        return done.getResponse();
    }

    @Test
    void aLegitimateLegacyOutput_isStillDownloadable_withTheSameTypeNameAndSize() throws Exception {
        Fixture f = fixtureWithContent("report.pdf", PDF_MIME, minimalPdf());
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        UUID jobId = completedJob(f);
        String legacy = f.tenantId() + "/tmp/watermarks/" + jobId + "/report.pdf";
        put(f, legacy, "%PDF-LEGACY");
        jdbc.update("update watermark_jobs set output_object_key = ?, output_size_bytes = ? where job_id = ?",
                legacy, (long) "%PDF-LEGACY".length(), jobId);

        var response = result(f, jobId);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("report-watermarked.pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf("%PDF-LEGACY".length()));
        assertThat(response.getContentAsString()).isEqualTo("%PDF-LEGACY");
    }

    @Test
    void anUnsafeKeyPointingAtASiblingJobsObject_cannotBeRead_andIsAnsweredAsGone() throws Exception {
        Fixture f = fixtureWithContent("report.pdf", PDF_MIME, minimalPdf());
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        UUID jobId = completedJob(f);
        UUID otherJob = UUID.randomUUID();
        put(f, f.tenantId() + "/tmp/watermarks/" + otherJob + "/their.pdf", "SIBLING SECRET");
        jdbc.update("update watermark_jobs set output_object_key = ? where job_id = ?",
                f.tenantId() + "/tmp/watermarks/" + jobId + "/../" + otherJob + "/their.pdf", jobId);
        int auditsBefore = auditCount(com.oak.dms.audit.catalog.AuditActionCatalog.WATERMARK_RESULT_DOWNLOADED, jobId);

        var response = result(f, jobId);

        assertThat(response.getStatus()).isEqualTo(410);
        String body = response.getContentAsString();
        assertThat(body).contains("WATERMARK_EXPIRED").doesNotContain("SIBLING SECRET").doesNotContain("their")
                .doesNotContain(otherJob.toString()).doesNotContain("..");
        assertThat(auditCount(com.oak.dms.audit.catalog.AuditActionCatalog.WATERMARK_RESULT_DOWNLOADED, jobId))
                .as("nothing was downloaded, so nothing is audited as a download").isEqualTo(auditsBefore);
        assertThat(exists(f, f.tenantId() + "/tmp/watermarks/" + otherJob + "/their.pdf")).as("untouched").isTrue();
    }

    @Test
    void anUnsafeKeyPointingAtAnotherTenantsObject_cannotBeRead_andIsAnsweredAsGone() throws Exception {
        Fixture other = fixtureWithContent("other.pdf", PDF_MIME, minimalPdf());
        Fixture f = fixtureWithContent("report.pdf", PDF_MIME, minimalPdf());
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        UUID jobId = completedJob(f);
        UUID otherJob = UUID.randomUUID();
        String foreignKey = other.tenantId() + "/tmp/watermarks/" + otherJob + "/their.pdf";
        put(f, foreignKey, "FOREIGN SECRET");            // reachable from this job's own storage location
        jdbc.update("update watermark_jobs set output_object_key = ? where job_id = ?", foreignKey, jobId);

        var response = result(f, jobId);

        assertThat(response.getStatus()).isEqualTo(410);
        assertThat(response.getContentAsString()).contains("WATERMARK_EXPIRED").doesNotContain("FOREIGN SECRET")
                .doesNotContain(other.tenantId().toString()).doesNotContain(otherJob.toString());
    }
}
