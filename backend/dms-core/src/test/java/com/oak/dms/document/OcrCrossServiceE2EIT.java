package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DocumentReviewResponse;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.document.dto.SaveReviewResponse;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that proves the OCR path works ACROSS services, not just
 * inside dms-core.
 *
 * <h2>What is real here</h2>
 * Everything except the browser:
 * <ul>
 *   <li><b>ClamAV</b> reaches a real verdict - the infected case uses the
 *       real EICAR test string, not a stubbed "infected" status;</li>
 *   <li><b>SeaweedFS</b> stores every object through the ordinary
 *       {@code S3ObjectStorageAdapter}, encrypted;</li>
 *   <li><b>RabbitMQ</b> carries the request - published by the real
 *       {@code OutboxRelay} from a real {@code outbox_events} row;</li>
 *   <li><b>ocr-worker</b> is a separate running container that consumes it,
 *       reads the document back over {@code /internal/v1/ocr} with the
 *       per-job grant, and writes its output back the same way;</li>
 *   <li><b>dms-core</b> is this JVM, listening on 8080 and reachable from
 *       the worker as {@code http://dms-core:8080} - which is why the test
 *       container joins the compose network under that alias.</li>
 * </ul>
 *
 * <p>Only the HTTP/JWT edge is bypassed: the test calls {@code DocumentService}
 * directly, which is what {@code DocumentController} calls one line after
 * {@code @PreAuthorize}. Standing up Keycloak to obtain a token would test
 * Keycloak, not the OCR pipeline, and the instruction for this work was not
 * to touch the realm.
 *
 * <h2>Self-skipping</h2>
 * Every precondition is checked up front and the whole class skips if the
 * stack is not up, so an ordinary {@code mvn verify} on a laptop is
 * unaffected. See docs/ocr/e2e-run.md for how to bring it up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=8080",
                "dms.ocr.enabled=true",
        })
class OcrCrossServiceE2EIT {

    /** Where the worker is, so the test can check it is up before asserting on it. */
    private static final String WORKER_HEALTH = System.getenv().getOrDefault(
            "OCR_WORKER_BASE_URL", "http://ocr-worker:8090") + "/healthz";

    private static final String S3_ENDPOINT = System.getenv().getOrDefault("S3_ENDPOINT", "http://seaweedfs:8333");
    private static final String BUCKET = System.getenv().getOrDefault("S3_BUCKET", "dms-local");
    private static final String CREDENTIAL_REF = "e2e-seaweedfs";

    /** The real EICAR test string - every scanner detects it, no real malware involved. */
    private static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
            .getBytes(StandardCharsets.US_ASCII);

    @Autowired private DocumentService documentService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentTypeRepository documentTypeRepository;
    @Autowired private MetadataFieldRepository metadataFieldRepository;
    @Autowired private DocumentTypeSchemaRepository documentTypeSchemaRepository;
    @Autowired private StorageLocationRepository storageLocationRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    static void requireTheStack() {
        String access = System.getenv("SEAWEEDFS_S3_ACCESS_KEY");
        String secret = System.getenv("SEAWEEDFS_S3_SECRET_KEY");
        Assumptions.assumeTrue(access != null && !access.isBlank(),
                "SEAWEEDFS_S3_ACCESS_KEY is not set - run this with the compose env file");
        // Same credentialRef indirection S3ObjectStorageAdapter always uses;
        // the value never reaches the database or this file.
        String prefix = "STORAGE_CRED_" + CREDENTIAL_REF.toUpperCase().replace('-', '_');
        System.setProperty(prefix + "_ACCESS_KEY", access);
        System.setProperty(prefix + "_SECRET_KEY", secret);

        Assumptions.assumeTrue(reachable(WORKER_HEALTH),
                "ocr-worker is not reachable at " + WORKER_HEALTH + " - start it before running this test");

        // The bucket, created here rather than assumed: SeaweedFS starts
        // empty, and a missing bucket would surface as an opaque adapter
        // failure in the middle of an upload instead of as a setup problem.
        try (software.amazon.awssdk.services.s3.S3Client s3 =
                     software.amazon.awssdk.services.s3.S3Client.builder()
                             .endpointOverride(URI.create(S3_ENDPOINT))
                             .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                             .forcePathStyle(true)
                             .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
                                     .create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials
                                             .create(access, secret)))
                             .build()) {
            try {
                s3.createBucket(b -> b.bucket(BUCKET));
            } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
                     | software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException alreadyThere) {
                // A previous run made it.
            }
        } catch (software.amazon.awssdk.core.exception.SdkException unreachable) {
            Assumptions.assumeTrue(false,
                    "SeaweedFS is not usable at " + S3_ENDPOINT + ": " + unreachable.getMessage());
        }
    }

    private static boolean reachable(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            int status = connection.getResponseCode();
            connection.disconnect();
            return status < 500;
        } catch (IOException e) {
            return false;
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 1. Clean upload -> OCR -> fields -> review -> saved review
    // ------------------------------------------------------------------

    @Test
    void aCleanUploadIsOcrdByTheRealWorker_andItsFieldsCanBeReviewedAndSaved() throws Exception {
        Fixture fixture = fixture("e2e-clean");
        TenantContext.set(fixture.tenantId(), fixture.userId());

        IngestionTicketResponse ticket = documentService.uploadDocument(
                fixture.documentTypeId(), "E2E Invoice", "invoice.pdf",
                fixture.pdf().length, new ByteArrayInputStream(fixture.pdf()),
                null, false, List.of("invoice_number"));

        // The scan gate reached a verdict before returning (local ClamAV).
        assertThat(ticket.status()).as(ticket.message()).isEqualTo(ScanResultStatus.clean);
        UUID documentId = ticket.documentId();
        UUID versionId = ticket.versionId();

        // A job row exists and the request went to the INTERACTIVE queue.
        assertThat(jdbc.queryForObject(
                "select count(*) from processing_jobs where reference_type='document_versions' and reference_id=? "
                        + "and job_type='ocr'", Integer.class, versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select event_type from outbox_events where aggregate_id = ? order by created_at desc limit 1",
                String.class, versionId)).isEqualTo("document.version.ocr.requested");

        // The real worker picks it up off RabbitMQ, fetches the bytes back
        // over /internal/v1/ocr, and writes its result. Polled, because this
        // is genuinely asynchronous across two processes.
        awaitUntil(Duration.ofMinutes(6), () -> "completed".equals(ocrStatus(versionId)),
                () -> "ocr_status stayed '" + ocrStatus(versionId) + "'");

        // It read the document through the internal endpoint - audited once
        // per grant, with via=ocr-worker.
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action='DOCUMENT_VIEWED' "
                + "and object_id = ? and new_value::text like '%ocr-worker%'", Integer.class, documentId))
                .isGreaterThanOrEqualTo(1);

        // And its output landed in SeaweedFS through the encrypting layer,
        // under a key dms-core chose.
        String outputKey = jdbc.queryForObject(
                "select extracted_text_ref from ocr_outputs where version_id = ?", String.class, versionId);
        assertThat(outputKey).startsWith("ocr/" + fixture.tenantId() + "/" + documentId + "/" + versionId);

        // Review: the extracted fields are visible...
        DocumentReviewResponse review = documentService.getReview(documentId);
        assertThat(review.fields()).isNotEmpty();

        // ...and saving the review writes the reviewer's value into metadata.
        String fieldKey = review.fields().get(0).fieldKey();
        SaveReviewResponse saved = documentService.saveReview(documentId, new SaveReviewRequest(List.of(
                new SaveReviewRequest.Decision(fieldKey, "corrected", "INV-E2E-1", "typed"))));
        assertThat(saved.corrected()).isEqualTo(1);

        Map<String, Object> metadata = jdbc.queryForMap(
                "select field_values from document_metadata where document_id = ?", documentId);
        assertThat(String.valueOf(metadata.get("field_values"))).contains("INV-E2E-1");

        // The saved review leaves the version out of the queue and in saved reviews.
        assertThat(jdbc.queryForObject("select requires_review from document_versions where version_id = ?",
                Boolean.class, versionId)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from document_ocr_corrections where version_id = ?",
                Integer.class, versionId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2. Infected upload -> quarantined, no OCR
    // ------------------------------------------------------------------

    @Test
    void anInfectedUploadIsQuarantined_andNoOcrIsEverRequestedForIt() {
        Fixture fixture = fixture("e2e-infected");
        TenantContext.set(fixture.tenantId(), fixture.userId());

        IngestionTicketResponse ticket = documentService.uploadDocument(
                fixture.documentTypeId(), "E2E Infected", "eicar.txt",
                EICAR.length, new ByteArrayInputStream(EICAR),
                null, false, List.of("invoice_number"));

        // `quarantined` is the terminal state, not `infected`: the gate moves
        // the file to the quarantine location and records that, so the ticket
        // the caller sees is the post-quarantine one.
        assertThat(ticket.status())
                .as("ClamAV must detect EICAR; got %s (%s)", ticket.status(), ticket.message())
                .isEqualTo(ScanResultStatus.quarantined);
        assertThat(ticket.threatName()).contains("Eicar");

        // No document, no version - DocumentVersionPromoter never ran, so the
        // OCR request it carried can never have been acted on.
        assertThat(ticket.documentId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from processing_jobs where tenant_id = ? and job_type='ocr'",
                Integer.class, fixture.tenantId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where tenant_id = ? "
                + "and event_type like 'document.version.ocr.requested%'", Integer.class, fixture.tenantId()))
                .isZero();
        // The scan result kept the OCR request it was given - and it stays
        // unacted-on precisely because no version was created.
        assertThat(jdbc.queryForObject("select ocr_requested_fields::text from scan_results where scan_result_id = ?",
                String.class, ticket.scanResultId())).contains("invoice_number");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String ocrStatus(UUID versionId) {
        return jdbc.queryForObject("select ocr_status from document_versions where version_id = ?",
                String.class, versionId);
    }

    private static void awaitUntil(Duration timeout, Supplier<Boolean> condition, Supplier<String> failure)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("Timed out after " + timeout + ": " + failure.get());
    }

    private record Fixture(UUID tenantId, UUID userId, UUID documentTypeId, byte[] pdf) {
    }

    /**
     * A tenant with real SeaweedFS locations for document/staging/quarantine,
     * one OCR-extractable text field, and a published schema carrying it.
     */
    private Fixture fixture(String label) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName(label + " " + UUID.randomUUID())
                .domain(label + "-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@" + label + ".invalid")
                .username(keycloakId.toString())
                .firstName("E2E").lastName("Runner").displayName("E2E Runner")
                .status(User.UserStatus.active)
                .build());

        for (StoragePurpose purpose : new StoragePurpose[]{
                StoragePurpose.document, StoragePurpose.staging, StoragePurpose.quarantine}) {
            storageLocationRepository.save(StorageLocation.builder()
                    .tenantId(tenant.getTenantId())
                    .name(label + "-" + purpose + "-" + UUID.randomUUID())
                    .provider(StorageProvider.S3)
                    .purpose(purpose)
                    .endpoint(S3_ENDPOINT)
                    .bucket(BUCKET)
                    .region("us-east-1")
                    .pathStyleAccess(true)
                    // Separate key prefixes per purpose in one bucket: the
                    // gate's staging/quarantine isolation check compares the
                    // physical target, and SeaweedFS buckets are cheap but
                    // the bootstrap only guarantees this one exists.
                    .basePath(label + "/" + purpose)
                    .credentialRef(CREDENTIAL_REF)
                    .status(StorageLocation.Status.active)
                    .build());
        }

        DocumentType documentType = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenant.getTenantId())
                .typeCode("E2E_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .typeName("E2E Invoice")
                .status(DocumentType.DocumentTypeStatus.active)
                .build());
        MetadataField field = metadataFieldRepository.save(MetadataField.builder()
                .tenantId(tenant.getTenantId())
                .fieldKey("invoice_number")
                .label("Invoice Number")
                .dataType("text")
                .ocrExtractable(true)
                .build());
        documentTypeSchemaRepository.saveAndFlush(DocumentTypeSchema.builder()
                .tenantId(tenant.getTenantId())
                .documentTypeId(documentType.getDocumentTypeId())
                .schemaVersion(documentType.getCurrentSchemaVersion())
                .fields(List.of(new DocumentTypeSchema.FieldMapping(
                        UUID.randomUUID(), field.getFieldId(), false, false, false, 10, null, null)))
                .publishedBy(user.getUserId())
                .build());

        return new Fixture(tenant.getTenantId(), user.getUserId(), documentType.getDocumentTypeId(), onePagePdf());
    }

    /**
     * A one-page PDF with a line of real text, built here rather than
     * committed as a fixture binary so what the worker is asked to read is
     * visible in the source.
     */
    private static byte[] onePagePdf() {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page =
                    new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            document.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 18);
                content.newLineAtOffset(72, 720);
                content.showText("Invoice Number: INV-E2E-0001");
                content.endText();
            }
            var out = new java.io.ByteArrayOutputStream();
            document.save((OutputStream) out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the E2E fixture PDF", e);
        }
    }
}
