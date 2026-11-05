package com.oak.dms.document;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.ocrworker.OcrJobTokenCodec;
import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.ProcessingJob;
import com.oak.dms.ocrworker.ProcessingJobRepository;
import com.oak.dms.security.auth.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every way {@code /internal/v1/ocr} must refuse to hand over a document.
 *
 * <h2>Why this is a standalone MockMvc test and not an integration test</h2>
 * What is under test is an ordered list of authorization checks, and each one
 * has to be provably reachable on its own. Driving that through a real
 * database means getting a version into one precise state per case - a live
 * job, no job, a finished job, an unscanned version - which the V39 scan-gate
 * trigger exists specifically to make hard. Mocking the two repositories lets
 * every refusal be stated directly, and the checks themselves are pure logic
 * over values the controller was handed.
 *
 * <p>The real signing is kept: {@link OcrJobTokenCodec} is the production
 * class with a real secret, so a forged, re-scoped or expired token fails for
 * the reason it would in production, not because a mock said so.
 *
 * <h2>Why it is not reachable from outside</h2>
 * nginx does not forward {@code /internal/} (see
 * docs/ocr/internal-endpoint.md); that is a deployment fact and cannot be
 * asserted from a unit test. What IS asserted here is that the endpoint is
 * safe even if that rule is removed or a second ingress is added - which is
 * the property that actually protects the document.
 */
class OcrInternalContentControllerTest {

    private static final String SECRET = "test-job-token-secret-not-used-anywhere-else";
    private static final String STATIC_TOKEN = "the-internal-service-token";
    private static final String PATH = "/internal/v1/ocr/versions/{versionId}/content";

    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final ProcessingJobRepository jobs = mock(ProcessingJobRepository.class);
    private final VersionContent versionContent = mock(VersionContent.class);
    private final AuditService auditService = mock(AuditService.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private final OcrArtifactWriter artifactWriter = mock(OcrArtifactWriter.class);
    private final OcrJobTokenCodec codec = new OcrJobTokenCodec(SECRET);
    private final OcrProperties properties = new OcrProperties();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID otherVersionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setInternalServiceToken(STATIC_TOKEN);

        DocumentVersion version = DocumentVersion.builder()
                .versionId(versionId).documentId(documentId).tenantId(tenantId)
                .versionNumber(1).originalFilename("invoice.pdf").sizeBytes(4)
                .build();
        when(versions.findById(versionId)).thenReturn(Optional.of(version));
        when(versions.findById(otherVersionId)).thenReturn(Optional.of(DocumentVersion.builder()
                .versionId(otherVersionId).documentId(UUID.randomUUID()).tenantId(tenantId).build()));
        when(jobs.findById(jobId)).thenReturn(Optional.of(ProcessingJob.builder()
                .jobId(jobId)
                .tenantId(tenantId)
                .jobType(ProcessingJob.JOB_TYPE_OCR)
                .referenceType(ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION)
                .referenceId(versionId)
                .status(ProcessingJob.STATUS_PROCESSING)
                .build()));
        when(versionContent.open(any())).thenAnswer(call -> new DownloadedDocument(
                new ByteArrayInputStream("%PDF".getBytes(StandardCharsets.UTF_8)), 4,
                "application/pdf", "invoice.pdf"));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new OcrInternalContentController(versions, jobs, versionContent, codec,
                        properties, auditService, artifactWriter))
                // The real advice, so a refusal's status code here is the one
                // a caller would actually get. Without it standalone MockMvc
                // surfaces DocumentNotScannedException as a servlet failure
                // and the scan-gate assertion would be testing MockMvc, not
                // the endpoint.
                .setControllerAdvice(new com.oak.dms.common.exception.GlobalExceptionHandler(
                        mock(com.oak.dms.audit.controller.AccessDeniedAuditor.class)))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String jobGrant(UUID forJob, UUID forVersion, UUID forTenant, Duration validFor) {
        Instant now = Instant.now();
        return codec.issueForJob(forJob, forVersion, forTenant, now.minus(Duration.ofMinutes(1)),
                now.plus(validFor));
    }

    /** The baseline: with both credentials correct, the bytes come back. */
    @Test
    void aLiveJobsOwnGrant_servesTheDocument_andNeverCachesIt() throws Exception {
        // The bytes are streamed (StreamingResponseBody), which runs on another thread: read the body only
        // after the async dispatch completes, or the assertion races the writer and sometimes sees nothing.
        MvcResult started = mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", jobGrant(jobId, versionId, tenantId, Duration.ofMinutes(30))))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes("%PDF".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void withoutTheStaticToken_or_withTheWrongOne_nothingIsServed() throws Exception {
        String grant = jobGrant(jobId, versionId, tenantId, Duration.ofMinutes(30));

        mockMvc.perform(get(PATH, versionId).header("X-Ocr-Job-Token", grant))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", "not-the-token")
                        .header("X-Ocr-Job-Token", grant))
                .andExpect(status().isUnauthorized());
        // Same length as the real one, so a comparison that leaked length
        // would not be what made this pass.
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", "X".repeat(STATIC_TOKEN.length()))
                        .header("X-Ocr-Job-Token", grant))
                .andExpect(status().isUnauthorized());

        verify(versionContent, never()).open(any());
    }

    @Test
    void aMissingForgedOrTamperedJobToken_isRefused() throws Exception {
        String valid = jobGrant(jobId, versionId, tenantId, Duration.ofMinutes(30));

        mockMvc.perform(get(PATH, versionId).header("X-Internal-Token", STATIC_TOKEN))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", "not-a-token"))
                .andExpect(status().isUnauthorized());
        // Signed by a different secret: the payload is well-formed and says
        // exactly the right thing, and it still fails.
        String forged = new OcrJobTokenCodec("a-different-secret")
                .issueForJob(jobId, versionId, tenantId, Instant.now(), Instant.now().plusSeconds(600));
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", forged))
                .andExpect(status().isUnauthorized());
        // The payload edited after signing.
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", valid.substring(0, valid.length() - 2) + "xx"))
                .andExpect(status().isUnauthorized());

        verify(versionContent, never()).open(any());
    }

    /** A real grant, for a real version - just not the one in the path. */
    @Test
    void aGrantForAnotherVersion_cannotReadThisOne() throws Exception {
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token",
                                jobGrant(jobId, otherVersionId, tenantId, Duration.ofMinutes(30))))
                .andExpect(status().isUnauthorized());

        verify(versionContent, never()).open(any());
    }

    /**
     * The tenant is taken from the version row and compared with the grant's.
     * A grant that claims another tenant's id cannot read a version that does
     * not belong to that tenant - the claim is checked, never trusted.
     */
    @Test
    void aGrantClaimingAnotherTenant_cannotReadThisTenantsVersion() throws Exception {
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token",
                                jobGrant(jobId, versionId, otherTenantId, Duration.ofMinutes(30))))
                .andExpect(status().isUnauthorized());

        verify(versionContent, never()).open(any());
    }

    @Test
    void anExpiredGrant_isRefused() throws Exception {
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token",
                                jobGrant(jobId, versionId, tenantId, Duration.ofSeconds(-1))))
                .andExpect(status().isUnauthorized());

        verify(versionContent, never()).open(any());
    }

    /**
     * Finishing, failing or cancelling the job revokes its grant immediately,
     * without waiting for the expiry - which is the whole reason the job id is
     * in the token and checked against the database.
     */
    @Test
    void aGrantWhoseJobHasFinished_or_neverExisted_isRefused() throws Exception {
        when(jobs.findById(jobId)).thenReturn(Optional.of(ProcessingJob.builder()
                .jobId(jobId).tenantId(tenantId)
                .jobType(ProcessingJob.JOB_TYPE_OCR)
                .referenceType(ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION)
                .referenceId(versionId)
                // "completed" is the db-level value (V69's check constraint);
                // ProcessingJob has no constant for it because dms-core never
                // writes it - the worker does.
                .status("completed")
                .build()));
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", jobGrant(jobId, versionId, tenantId, Duration.ofMinutes(30))))
                .andExpect(status().isUnauthorized());

        UUID unknownJob = UUID.randomUUID();
        when(jobs.findById(unknownJob)).thenReturn(Optional.empty());
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", jobGrant(unknownJob, versionId, tenantId, Duration.ofMinutes(30))))
                .andExpect(status().isUnauthorized());

        verify(versionContent, never()).open(any());
    }

    /**
     * The scan gate applies here exactly as it does to a user download: the
     * endpoint does not open storage itself, it goes through
     * {@link VersionContent}, so an unscanned or quarantined version is
     * refused by the same code and the OCR path cannot become a way around it.
     */
    @Test
    void anUnscannedVersion_isRefusedByTheScanGate_notServed() throws Exception {
        when(versionContent.open(any())).thenThrow(new DocumentNotScannedException(documentId));

        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", jobGrant(jobId, versionId, tenantId, Duration.ofMinutes(30))))
                .andExpect(status().is4xxClientError());
    }

    /**
     * One job reads its document a bounded number of times. A worker looping
     * on the same grant is malfunctioning or malicious, and either way must
     * not be able to pull a document indefinitely.
     */
    @Test
    void aGrantThatKeepsReading_isCutOff() throws Exception {
        String grant = jobGrant(jobId, versionId, tenantId, Duration.ofMinutes(30));
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get(PATH, versionId)
                            .header("X-Internal-Token", STATIC_TOKEN)
                            .header("X-Ocr-Job-Token", grant))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", grant))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * With {@code dms.ocr.enabled=false} the route answers 404, not 401: a
     * deployment that does not run OCR should not advertise an OCR endpoint
     * that merely refuses you.
     */
    @Test
    void withOcrDisabled_theRouteIsNotThere() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(get(PATH, versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", jobGrant(jobId, versionId, tenantId, Duration.ofMinutes(30))))
                .andExpect(status().isNotFound());

        verify(versionContent, never()).open(any());
    }

    /**
     * An adhoc grant is for the review screen's reads only. It carries no job
     * id, so nothing can be revoked by finishing a job - which is exactly why
     * it must not be able to write an artifact back.
     */
    @Test
    void anAdhocGrant_cannotWriteAnArtifact() throws Exception {
        Instant now = Instant.now();
        String adhoc = codec.issueAdhoc(versionId, tenantId, now, now.plus(Duration.ofMinutes(2)));

        mockMvc.perform(put("/internal/v1/ocr/versions/{versionId}/output", versionId)
                        .header("X-Internal-Token", STATIC_TOKEN)
                        .header("X-Ocr-Job-Token", adhoc)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        verify(artifactWriter, never()).write(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }
}
