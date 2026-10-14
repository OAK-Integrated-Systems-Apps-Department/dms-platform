package com.oak.dms.ocrworker;

import com.oak.dms.document.Document;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.document.DocumentOcrFieldResult;
import com.oak.dms.document.DocumentOcrFieldResultRepository;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.messaging.RabbitMqConfig;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.entity.DocumentType.DocumentTypeStatus;
import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
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
 *
 * <h2>Run this against an isolated RabbitMQ - not a shared dev broker</h2>
 * {@code dms-core.q.ocr-events} is one well-known queue name every dms-core
 * instance listens on by design (production runs several replicas that
 * MUST compete for the same queue - that is the whole point of horizontal
 * scaling). This test's own listener is just one more competitor for that
 * queue. Run it on a machine where another dms-core instance (e.g. the
 * `oakdms-core` container from {@code infra/local/docker-compose.yml}) is
 * ALSO connected to the same broker, and RabbitMQ may hand your test's
 * message to that OTHER instance instead - which processes it against ITS
 * OWN database, leaves nothing for this test to see, and fails this test
 * with no exception anywhere (confirmed empirically: this test is
 * deterministic - passes 100% with no competing consumer attached, fails
 * 100% with one attached, both repeated across multiple runs). CI is
 * unaffected (its RabbitMQ is thrown away per job with nothing else
 * attached) - this is a local-dev-only caveat. Stop any other dms-core
 * instance pointed at the same broker before running this test by hand.
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
    private DocumentTypeSchemaRepository documentTypeSchemaRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private DocumentVersionRepository documentVersionRepository;
    @Autowired
    private DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    @Autowired
    private DocumentMetadataRepository documentMetadataRepository;
    @Autowired
    private ProcessingJobRepository processingJobRepository;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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

        // Field mappings live inside the published schema document on this
        // branch - there is no document_type_fields table any more.
        documentTypeSchemaRepository.saveAndFlush(DocumentTypeSchema.builder()
                .tenantId(tenantId)
                .documentTypeId(documentType.getDocumentTypeId())
                .schemaVersion(documentType.getCurrentSchemaVersion())
                .fields(List.of(new DocumentTypeSchema.FieldMapping(
                        UUID.randomUUID(), field.getFieldId(), false, false, false, 10, null, null)))
                .publishedBy(ownerUserId)
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
        // V39's scan gate: a version needs a document-purpose location and a
        // clean scan result linked to it, or the deferred trigger rejects the
        // insert at commit. See ScanGateFixture.
        UUID locationId = ScanGateFixture.documentLocation(jdbc, tenantId);
        // scan_results.document_version_id -> document_versions.version_id is
        // also a DEFERRABLE FK, so the scan result insert has to share this
        // same transaction too - not run and committed beforehand.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ScanGateFixture.cleanScanResult(jdbc, tenantId, versionId, documentType.getDocumentTypeId(), ownerUserId,
                    "live-it.pdf", "application/pdf", "deadbeef");
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
                    .storageLocationId(locationId)
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
            // document_metadata, keyed by field id (V36 replaced the
            // one-row-per-value table with this map).
            invoiceNumber = documentMetadataRepository
                    .findById(new com.oak.dms.metadata.entity.DocumentMetadataId(tenantId, documentId))
                    .map(com.oak.dms.metadata.entity.DocumentMetadata::getFieldValues)
                    .map(values -> values.get(field.getFieldId().toString()))
                    .map(value -> String.valueOf(value.v()));
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
