package com.oak.dms.document;

import com.oak.dms.metadata.DocumentType;
import com.oak.dms.metadata.DocumentTypeRepository;
import com.oak.dms.metadata.DocumentType.DocumentTypeStatus;
import com.oak.dms.ocrworker.ProcessingJob;
import com.oak.dms.ocrworker.ProcessingJobRepository;
import com.oak.dms.outbox.OutboxEvent;
import com.oak.dms.outbox.OutboxEventRepository;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real end-to-end proof that Phase 1 B.1's upload-path switch-over actually
 * works against the real Postgres schema (V36's partial unique index in
 * particular), not just that the code compiles: a real POST /api/v1/documents
 * request, through the real Spring Security filter chain and the real
 * DocumentController/DocumentServiceImpl, must leave behind a real 'queued'
 * processing_jobs row and a real document.version.ocr.requested outbox
 * event, both inserted in the SAME transaction as the version insert, and
 * document_versions.ocr_status must be 'pending' -- not 'processing' (the
 * pre-B.1 default, now reserved for a live ocr-worker claim).
 */
@SpringBootTest
class DocumentUploadOcrQueueingIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DocumentTypeRepository documentTypeRepository;
    @Autowired
    private DocumentVersionRepository documentVersionRepository;
    @Autowired
    private ProcessingJobRepository processingJobRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private MockMvc mockMvc;

    @Test
    void uploadingARealDocumentQueuesARealOcrJobAndOutboxEventInTheSameTransaction() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        UUID keycloakId = UUID.randomUUID();
        UUID tenantId = createTenantUserAndDocumentType(keycloakId);

        MockMultipartFile file = new MockMultipartFile(
                "file", "weird name with spaces & (parens).pdf",
                // Deliberately mislabeled Content-Type -- exercises B.5's
                // Tika sniff at the same time as B.1's queueing logic,
                // matching how a real browser upload behaves (confirmed
                // live earlier this session: Chrome sends
                // application/octet-stream for formats it has no
                // registered MIME type for).
                "application/octet-stream", MINIMAL_PDF_BYTES);

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("documentType", "general")
                        .with(jwt().jwt(builder -> builder.subject(keycloakId.toString())).authorities()))
                .andExpect(status().isCreated());

        List<DocumentVersion> versions = documentVersionRepository.findAll().stream()
                .filter(v -> v.getTenantId().equals(tenantId))
                .toList();
        assertThat(versions).hasSize(1);
        DocumentVersion version = versions.get(0);
        assertThat(version.getOcrStatus()).isEqualTo("pending");
        // Real Tika sniff (not the mislabeled application/octet-stream
        // header) correctly identified the real PDF bytes.
        assertThat(version.getMimeType()).isEqualTo("application/pdf");
        // Real filename sanitization: spaces/parens/ampersand are not path
        // separators or control characters, so they pass through
        // unmodified -- only real gap fixed (../ etc.) gets stripped.
        assertThat(version.getStorageObjectKey()).endsWith("weird name with spaces & (parens).pdf");

        List<ProcessingJob> jobs = processingJobRepository.findByReferenceTypeAndReferenceIdAndJobTypeAndStatusIn(
                ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION, version.getVersionId(), ProcessingJob.JOB_TYPE_OCR,
                List.of(ProcessingJob.STATUS_QUEUED, ProcessingJob.STATUS_PROCESSING));
        assertThat(jobs).hasSize(1);
        // 'queued' OR 'processing', and 0 or 1 attempts -- not because the
        // upload path is vague about which it writes (it always writes
        // 'queued' with attempts=0), but because this IT runs against the
        // live compose stack, where a real ocr-worker is consuming the very
        // queue this upload just published to. OutboxRelay polls every 2s
        // and the whole test takes ~3.5s, so on an idle worker the row is
        // legitimately already claimed by the time these lines run.
        //
        // Asserting 'queued' alone made this test pass only while the
        // worker happened to be busy -- it failed as soon as the pool was
        // free (observed during Phase 1 close-out: "expected: queued but
        // was: processing"). A claim is the queue path WORKING; a test
        // that fails because the system responded promptly is testing the
        // wrong thing. What actually belongs to uploadDocument -- exactly
        // one active job row, and the outbox event below, in the same
        // transaction as the version -- is still asserted exactly.
        assertThat(jobs.get(0).getStatus())
                .isIn(ProcessingJob.STATUS_QUEUED, ProcessingJob.STATUS_PROCESSING);
        assertThat(jobs.get(0).getAttempts()).isBetween(0, 1);

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(version.getVersionId())
                        && "document.version.ocr.requested".equals(e.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPayload()).contains(version.getVersionId().toString());

        // Phase 1 B.8: the polling contract the frontend branches on
        // distinguishes "queued" from "processing" at all -- only real
        // since B.1 made upload set 'pending' and left 'processing' for an
        // actual ocr-worker claim. Same live-worker race as above (a claim
        // rewrites ocr_status to 'processing'), so either is correct here;
        // what must never appear is "failed", and errorCode/friendlyError
        // (B.6) must stay absent while nothing has failed.
        mockMvc.perform(get("/api/v1/documents/" + version.getDocumentId() + "/ocr-status")
                        .with(jwt().jwt(builder -> builder.subject(keycloakId.toString())).authorities()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.oneOf("queued", "processing", "completed")))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.friendlyError").doesNotExist());
    }

    private UUID createTenantUserAndDocumentType(UUID keycloakId) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("B1 upload-queueing test tenant " + keycloakId)
                .domain("b1-upload-test-" + keycloakId + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@b1-upload-test.invalid")
                .username(keycloakId.toString())
                .firstName("B1")
                .lastName("UploadTest")
                .displayName("B1 UploadTest")
                .status(User.UserStatus.active)
                .build());

        // No documentTypeId set -- unlike Document/Tenant/User (client-
        // generated UUIDs), DocumentType's @Id uses Hibernate's own
        // @UuidGenerator. Real bug found live: assigning an id manually
        // here made Spring Data's isNew() check see a non-null id and
        // call merge() (an UPDATE) instead of persist() (an INSERT),
        // failing with a StaleObjectStateException since no such row
        // exists yet to update.
        documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenant.getTenantId())
                .typeCode("GENERAL")
                .typeName("General")
                .status(DocumentTypeStatus.active)
                .build());

        return tenant.getTenantId();
    }

    // The smallest byte sequence PDFium/Tika reliably recognize as "application/pdf"
    // -- a real minimal one-page PDF, not just the %PDF- magic bytes alone.
    private static final byte[] MINIMAL_PDF_BYTES = ("%PDF-1.4\n"
            + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
            + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
            + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n"
            + "trailer<</Root 1 0 R>>\n"
            + "%%EOF").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
}
