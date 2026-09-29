package com.oak.dms.ocrworker;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentMetadataValueRepository;
import com.oak.dms.document.DocumentOcrFieldResult;
import com.oak.dms.document.DocumentOcrFieldResultRepository;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.metadata.DocumentType;
import com.oak.dms.metadata.DocumentType.DocumentTypeStatus;
import com.oak.dms.metadata.DocumentTypeField;
import com.oak.dms.metadata.DocumentTypeFieldRepository;
import com.oak.dms.metadata.DocumentTypeRepository;
import com.oak.dms.metadata.MetadataField;
import com.oak.dms.metadata.MetadataFieldRepository;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live proof of the two things {@code OcrFieldsReconcilerTest} cannot
 * check against mocks: that the native scan query actually selects the
 * right rows out of real Postgres, and that the marker CAS really is
 * atomic.
 * <p>
 * Both matter more than usual here. The scan is hand-written SQL with two
 * correlated EXISTS clauses -- exactly the shape that silently returns
 * nothing (or everything) when one join column is wrong, which a mocked
 * repository can never reveal. And the whole no-locking design rests on
 * {@code markOcrFieldsAppliedIfUnset} returning 1 to precisely one caller.
 * <p>
 * Runs against the shared local compose database, like every other
 * {@code *IT} in this module, and cleans up the rows that would otherwise
 * accumulate across runs -- see {@code OcrEventsConsumerLiveIT}'s own
 * {@code tearDown} and V37's header for what a leaky fixture cost last
 * time.
 */
@SpringBootTest
class OcrFieldsReconcilerIT {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentTypeRepository documentTypeRepository;
    @Autowired private MetadataFieldRepository metadataFieldRepository;
    @Autowired private DocumentTypeFieldRepository documentTypeFieldRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    @Autowired private DocumentMetadataValueRepository documentMetadataValueRepository;
    @Autowired private ProcessingJobRepository processingJobRepository;
    @Autowired private OcrFieldsReconciler reconciler;
    @Autowired private OcrProperties ocrProperties;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID createdJobId;

    @AfterEach
    void tearDown() {
        if (createdJobId != null) {
            processingJobRepository.deleteById(createdJobId);
            createdJobId = null;
        }
    }

    /** A completed OCR job whose fields were never applied -- a dropped event. */
    private Seeded seedUnappliedVersion(OffsetDateTime finishedAt) {
        UUID tenantId = tenantRepository.save(Tenant.builder()
                .tenantName("OcrFieldsReconcilerIT tenant")
                .domain("ocr-reconciler-it-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build()).getTenantId();

        UUID keycloakId = UUID.randomUUID();
        UUID ownerUserId = userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@ocr-reconciler-it.invalid")
                .username(keycloakId.toString())
                .firstName("Reconciler").lastName("IT").displayName("Reconciler IT")
                .status(User.UserStatus.active)
                .build()).getUserId();

        DocumentType documentType = documentTypeRepository.save(DocumentType.builder()
                .tenantId(tenantId).typeCode("GENERAL").typeName("General")
                .status(DocumentTypeStatus.active).build());

        MetadataField field = metadataFieldRepository.save(MetadataField.builder()
                .tenantId(tenantId).fieldKey("invoice_number")
                .label("Invoice Number").dataType("text").build());

        documentTypeFieldRepository.save(DocumentTypeField.builder()
                .tenantId(tenantId)
                .documentTypeId(documentType.getDocumentTypeId())
                .fieldId(field.getFieldId())
                .schemaVersion(documentType.getCurrentSchemaVersion())
                .build());

        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        // documents.current_version_id <-> document_versions.document_id are
        // mutual DEFERRABLE FKs (V32); both inserts must share one transaction.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            documentRepository.save(Document.builder()
                    .documentId(documentId).tenantId(tenantId)
                    .documentTypeId(documentType.getDocumentTypeId())
                    .title("reconciler-it.pdf")
                    .latestVersionId(versionId).currentVersionId(versionId)
                    .ownerUserId(ownerUserId).build());
            documentVersionRepository.save(DocumentVersion.builder()
                    .versionId(versionId).documentId(documentId).tenantId(tenantId)
                    .versionNumber(1).majorVersion(1).minorVersion(0)
                    .storageObjectKey("tenants/%s/documents/%s/versions/%s/x.pdf"
                            .formatted(tenantId, documentId, versionId))
                    .originalFilename("reconciler-it.pdf").mimeType("application/pdf")
                    .fileHash("deadbeef").sizeBytes(123).createdReason("initial_upload")
                    .scanStatus("clean")
                    // The state a dropped .completed event leaves behind:
                    // OCR finished and wrote its results, but nothing ever
                    // applied them to metadata.
                    .ocrStatus("completed")
                    .ocrFieldsAppliedAt(null)
                    .requestedFields("[]")
                    .build());
        });

        documentOcrFieldResultRepository.save(DocumentOcrFieldResult.builder()
                .fieldResultId(UUID.randomUUID())
                .tenantId(tenantId).documentId(documentId).versionId(versionId)
                .fieldKey("invoice_number").value("INV-RECONCILED-7")
                .confidence(BigDecimal.valueOf(0.93)).status("extracted")
                .createdAt(OffsetDateTime.now())
                .build());

        // finished_at is the reconciler's only clock -- document_versions has
        // no OCR-completion timestamp of its own.
        createdJobId = processingJobRepository.save(ProcessingJob.builder()
                .jobId(UUID.randomUUID()).tenantId(tenantId)
                .jobType(ProcessingJob.JOB_TYPE_OCR)
                .referenceType(ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION)
                .referenceId(versionId)
                // "completed" is a valid ck_processing_jobs_status value
                // (V33) but has no constant on ProcessingJob: dms-core only
                // ever writes 'queued' and reads the rest, so only the
                // statuses it writes are named there. ocr-worker owns this
                // transition.
                .status("completed")
                .attempts(1)
                .startedAt(finishedAt.minusSeconds(30))
                .finishedAt(finishedAt)
                .build()).getJobId();

        return new Seeded(tenantId, documentId, versionId, field.getFieldId());
    }

    private record Seeded(UUID tenantId, UUID documentId, UUID versionId, UUID fieldId) {
    }

    // ------------------------------------------------------------------
    @Test
    void appliesFieldsForAVersionTheCompletedEventNeverReached() {
        Seeded seeded = seedUnappliedVersion(
                OffsetDateTime.now().minusMinutes(ocrProperties.getStaleAfterMinutes() + 5));

        // The real query, against real Postgres.
        List<DocumentVersion> found = documentVersionRepository.findCompletedWithUnappliedOcrFields(
                OffsetDateTime.now().minusMinutes(ocrProperties.getStaleAfterMinutes()),
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(found).extracting(DocumentVersion::getVersionId).contains(seeded.versionId());

        reconciler.reconcileOnce();

        var values = documentMetadataValueRepository
                .findByDocumentIdAndTenantIdAndDeletedFalse(seeded.documentId(), seeded.tenantId());
        assertThat(values)
                .filteredOn(v -> v.getFieldId().equals(seeded.fieldId()))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.getValueText()).isEqualTo("INV-RECONCILED-7");
                    assertThat(v.getSource()).isEqualTo("ocr");
                });

        assertThat(documentVersionRepository.findById(seeded.versionId()))
                .get()
                .extracting(DocumentVersion::getOcrFieldsAppliedAt)
                .isNotNull();
    }

    @Test
    void ignoresAVersionThatFinishedTooRecentlyToBeAMiss() {
        /* The normal path is ocr-worker commit -> 2s relay poll ->
         * listener. A version that finished seconds ago is in flight, not
         * lost, and warning about it would make every warning meaningless. */
        Seeded seeded = seedUnappliedVersion(OffsetDateTime.now().minusSeconds(5));

        List<DocumentVersion> found = documentVersionRepository.findCompletedWithUnappliedOcrFields(
                OffsetDateTime.now().minusMinutes(ocrProperties.getStaleAfterMinutes()),
                org.springframework.data.domain.PageRequest.of(0, 50));

        assertThat(found).extracting(DocumentVersion::getVersionId).doesNotContain(seeded.versionId());
    }

    @Test
    void theMarkerCasLetsExactlyOneCallerClaimAVersion() {
        /* The entire no-locking design rests on this. If both calls could
         * return 1, two instances would both log about the same lost event
         * and both write the same fields. */
        Seeded seeded = seedUnappliedVersion(OffsetDateTime.now().minusMinutes(30));
        OffsetDateTime now = OffsetDateTime.now();

        int first = documentVersionRepository.markOcrFieldsAppliedIfUnset(seeded.versionId(), now);
        int second = documentVersionRepository.markOcrFieldsAppliedIfUnset(seeded.versionId(), now);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    void anAlreadyAppliedVersionIsNeverFoundAgain() {
        Seeded seeded = seedUnappliedVersion(OffsetDateTime.now().minusMinutes(30));
        documentVersionRepository.markOcrFieldsApplied(seeded.versionId(), OffsetDateTime.now());

        List<DocumentVersion> found = documentVersionRepository.findCompletedWithUnappliedOcrFields(
                OffsetDateTime.now().minusMinutes(ocrProperties.getStaleAfterMinutes()),
                org.springframework.data.domain.PageRequest.of(0, 50));

        assertThat(found).extracting(DocumentVersion::getVersionId).doesNotContain(seeded.versionId());
    }
}
