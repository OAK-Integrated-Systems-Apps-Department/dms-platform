package com.oak.dms.ocrworker;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentMetadataValueRepository;
import com.oak.dms.document.DocumentOcrFieldResult;
import com.oak.dms.document.DocumentOcrFieldResultRepository;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.messaging.RabbitMqConfig;
import com.oak.dms.metadata.DocumentType;
import com.oak.dms.metadata.DocumentType.DocumentTypeStatus;
import com.oak.dms.metadata.DocumentTypeField;
import com.oak.dms.metadata.DocumentTypeFieldRepository;
import com.oak.dms.metadata.MetadataField;
import com.oak.dms.metadata.MetadataFieldRepository;
import com.oak.dms.metadata.DocumentTypeRepository;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end proof that OcrEventsConsumer actually receives a message
 * over the real RabbitMQ connection and applies it -- not just that its own
 * dispatch logic is correct against mocks (OcrEventsConsumerTest), and not
 * just that the queue/binding/DLX topology was declared correctly
 * (confirmed separately via `rabbitmqctl list_bindings` against the real
 * broker). Publishes a real document.version.ocr.completed message via the
 * real RabbitTemplate bean, backed by a real document_ocr_field_results row
 * (simulating what ocr-worker itself would have already written) and a
 * real one-field schema, then polls document_metadata_values for the
 * result -- the actual thing a human would see change.
 */
@SpringBootTest
class OcrEventsConsumerLiveIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DocumentTypeRepository documentTypeRepository;
    @Autowired
    private MetadataFieldRepository metadataFieldRepository;
    @Autowired
    private DocumentTypeFieldRepository documentTypeFieldRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private DocumentVersionRepository documentVersionRepository;
    @Autowired
    private DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    @Autowired
    private DocumentMetadataValueRepository documentMetadataValueRepository;
    @Autowired
    private ProcessingJobRepository processingJobRepository;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** The one row this test leaves behind that outlives its usefulness: an
     * active (`processing`) processing_jobs row holds V36's partial unique
     * index slot for its version, so it is not inert test detritus the way
     * the tenant/document/version rows are. Deleted in {@link #tearDown()}.
     */
    private UUID createdJobId;

    /** This IT runs against the shared local compose database, not a
     * throwaway one, so anything it leaves in an ACTIVE state accumulates
     * across runs. Four such rows had built up before this cleanup existed.
     *
     * Only the processing_jobs row is removed: the tenant/user/document/
     * version/metadata rows it also creates are inert history, and deleting
     * them would mean unpicking a chain of foreign keys for no benefit.
     */
    @AfterEach
    void tearDown() {
        if (createdJobId != null) {
            processingJobRepository.deleteById(createdJobId);
            createdJobId = null;
        }
    }

    @Test
    void aRealCompletedEventPublishedToRabbitMqEndsUpAsARealMetadataValue() {
        UUID tenantId = tenantRepository.save(Tenant.builder()
                .tenantName("OcrEventsConsumerLiveIT tenant")
                .domain("ocr-events-live-it-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build()).getTenantId();

        // documents.owner_user_id is a real FK into users -- a bare
        // UUID.randomUUID() (this test's first draft) violates it.
        UUID keycloakId = UUID.randomUUID();
        UUID ownerUserId = userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@ocr-events-live-it.invalid")
                .username(keycloakId.toString())
                .firstName("Live")
                .lastName("IT")
                .displayName("Live IT")
                .status(User.UserStatus.active)
                .build()).getUserId();

        DocumentType documentType = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenantId)
                .typeCode("GENERAL")
                .typeName("General")
                .status(DocumentTypeStatus.active)
                .build());

        MetadataField field = metadataFieldRepository.save(MetadataField.builder()
                .tenantId(tenantId)
                .fieldKey("invoice_number")
                .label("Invoice Number")
                .dataType("text")
                .build());

        documentTypeFieldRepository.save(DocumentTypeField.builder()
                .tenantId(tenantId)
                .documentTypeId(documentType.getDocumentTypeId())
                .fieldId(field.getFieldId())
                .schemaVersion(documentType.getCurrentSchemaVersion())
                .build());

        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        // documents.current_version_id <-> document_versions.document_id
        // are mutual FKs, DEFERRABLE INITIALLY DEFERRED (V32) specifically
        // so both rows can be inserted in either order within ONE
        // transaction. Two independent repository.save() calls (Spring
        // Data's default @Transactional(REQUIRED) with no transaction
        // already active) each commit on their own, defeating that -- the
        // real uploadDocument method gets this for free since both inserts
        // already share its one @Transactional method; this test needs an
        // explicit transaction wrapping both saves to get the same effect.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            documentRepository.save(Document.builder()
                    .documentId(documentId)
                    .tenantId(tenantId)
                    .documentTypeId(documentType.getDocumentTypeId())
                    .title("live-it.pdf")
                    .latestVersionId(versionId)
                    .currentVersionId(versionId)
                    .ownerUserId(ownerUserId)
                    .build());
            documentVersionRepository.save(DocumentVersion.builder()
                    .versionId(versionId)
                    .documentId(documentId)
                    .tenantId(tenantId)
                    .versionNumber(1)
                    .majorVersion(1)
                    .minorVersion(0)
                    .storageObjectKey("tenants/%s/documents/%s/versions/%s/live-it.pdf".formatted(tenantId, documentId, versionId))
                    .originalFilename("live-it.pdf")
                    .mimeType("application/pdf")
                    .fileHash("deadbeef")
                    .sizeBytes(123)
                    .createdReason("initial_upload")
                    .scanStatus("clean")
                    .ocrStatus("processing")
                    .requestedFields("[]")
                    .build());
        });

        // Simulates what ocr-worker itself already wrote directly to
        // Postgres before publishing the event -- this consumer never
        // writes document_ocr_field_results, only reads it.
        documentOcrFieldResultRepository.save(DocumentOcrFieldResult.builder()
                .fieldResultId(UUID.randomUUID())
                .tenantId(tenantId)
                .documentId(documentId)
                .versionId(versionId)
                .fieldKey("invoice_number")
                .value("INV-LIVE-42")
                .confidence(BigDecimal.valueOf(0.97))
                .status("extracted")
                // NOT NULL with no @Builder.Default on the entity -- it's a
                // read-only mirror of a table ocr-worker writes directly via
                // raw SQL, so this test is the only Java-side insert there is.
                .createdAt(java.time.OffsetDateTime.now())
                .build());

        // Simulates the claim a real ocr-worker would have made -- the
        // staleness check needs a real processing_jobs row to compare
        // the event's attempt against.
        //
        // startedAt AND leaseExpiresAt are both set because a real claim
        // sets both (ocr-worker's repository.py#claim_job writes
        // status/attempts/started_at/lease_expires_at in one UPDATE).
        // Omitting them made this the ONLY writer in the system that could
        // produce a 'processing' row with no lease -- and such a row is
        // invisible to the reaper's `lease_expires_at < now()` scan, so it
        // sat in 'processing' forever, holding V36's partial unique index
        // slot for its version. Four had accumulated in the local database
        // from past runs of this test (see docs/ocr/phase1-closeout.md,
        // "Still open after this" #3). V37 now forbids the shape outright.
        OffsetDateTime claimedAt = OffsetDateTime.now();
        createdJobId = processingJobRepository.save(ProcessingJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(tenantId)
                .jobType(ProcessingJob.JOB_TYPE_OCR)
                .referenceType(ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION)
                .referenceId(versionId)
                .status(ProcessingJob.STATUS_PROCESSING)
                .attempts(1)
                .startedAt(claimedAt)
                .leaseExpiresAt(claimedAt.plusSeconds(1800))  // OCR_LEASE_DURATION_S's default
                .build()).getJobId();

        // Map.of() tops out at 10 key-value pairs -- this payload has 11,
        // so a mutable map is needed instead.
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("event_type", RabbitMqConfig.ROUTING_KEY_OCR_COMPLETED);
        payload.put("tenant_id", tenantId.toString());
        payload.put("document_id", documentId.toString());
        payload.put("version_id", versionId.toString());
        payload.put("ocr_output_id", UUID.randomUUID().toString());
        payload.put("extracted_text_ref", "ocr/fake/ref.json");
        payload.put("page_count", 1);
        payload.put("flags", Map.of("low_confidence", false, "requires_review", false));
        payload.put("attempt", 1);
        payload.put("occurred_at", java.time.OffsetDateTime.now().toString());

        rabbitTemplate.convertAndSend(RabbitMqConfig.DMS_EVENTS_EXCHANGE, RabbitMqConfig.ROUTING_KEY_OCR_COMPLETED, payload);

        // Real async delivery over the real broker -- poll rather than
        // assert immediately (no awaitility dependency in this module;
        // a plain manual loop is enough for a one-off wait like this).
        Optional<String> invoiceNumber = Optional.empty();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var values = documentMetadataValueRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId);
            invoiceNumber = values.stream()
                    .filter(v -> v.getFieldId().equals(field.getFieldId()))
                    .map(v -> v.getValueText())
                    .findFirst();
            if (invoiceNumber.isPresent()) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(invoiceNumber).contains("INV-LIVE-42");
    }
}
