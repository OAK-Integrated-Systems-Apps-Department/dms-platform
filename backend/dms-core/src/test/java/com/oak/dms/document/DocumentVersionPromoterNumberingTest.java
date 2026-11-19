package com.oak.dms.document;

import com.oak.dms.checkout.SignedContentGuard;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.storage.entity.StorageLocation;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DOC-08: a new version's number is assigned in {@code persist}, from the newest version row it reads once it holds the
 * document's row lock - never taken from the plan {@code prepare} made earlier, without a lock. The repository stub
 * plays the database: {@code latest} is whatever row is the newest one "now", so a test can change it between prepare
 * and persist exactly as another promotion committing in that gap would.
 */
class DocumentVersionPromoterNumberingTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID uploader = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    private DocumentVersionRepository versions;
    private DocumentVersionPromoter promoter;
    private Document document;
    private DocumentVersion latest;

    @BeforeEach
    void wire() {
        latest = row(1, 1, 0);
        document = Document.builder().documentId(documentId).tenantId(tenantId).documentTypeId(UUID.randomUUID())
                .title("contract.docx").latestVersionId(latest.getVersionId()).currentVersionId(latest.getVersionId())
                .ownerUserId(uploader).build();
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)).thenAnswer(i -> Optional.of(document));
        when(documents.lockByDocumentIdAndTenantId(documentId, tenantId)).thenAnswer(i -> Optional.of(document));
        versions = mock(DocumentVersionRepository.class);
        when(versions.findTopByDocumentIdOrderByVersionNumberDesc(documentId)).thenAnswer(i -> Optional.of(latest));
        com.oak.dms.storage.service.StorageLocationResolver resolver =
                mock(com.oak.dms.storage.service.StorageLocationResolver.class);
        when(resolver.resolveForTenant(any(), any())).thenReturn(
                StorageLocation.builder().storageLocationId(UUID.randomUUID()).build());
        promoter = new DocumentVersionPromoter(documents, versions, resolver,
                mock(com.oak.dms.ocrworker.OcrEnqueueService.class), new com.oak.dms.ocrworker.OcrProperties(),
                mock(ApplicationEventPublisher.class), mock(com.oak.dms.user.UserRepository.class),
                mock(SignedContentGuard.class), mock(EntityManager.class));
    }

    private DocumentVersion row(int number, int major, int minor) {
        return DocumentVersion.builder().versionId(UUID.randomUUID()).documentId(documentId).tenantId(tenantId)
                .versionNumber(number).majorVersion(major).minorVersion(minor).versionLabel(major + "." + minor).build();
    }

    private ScanResult scan(String reason) {
        return ScanResult.builder().scanResultId(UUID.randomUUID()).tenantId(tenantId).status(ScanResultStatus.clean)
                .ingestionKind(IngestionKind.new_version).targetDocumentId(documentId).createdReason(reason)
                .originalFilename("x.docx").mimeType("application/vnd.test").fileHash("ab").sizeBytes(3L)
                .uploadedBy(uploader).startedAt(java.time.OffsetDateTime.now().minusMinutes(5)).build();
    }

    private DocumentPromoter.PromotionPlan prepare(ScanResult scan) {
        DocumentPromoter.PromotionPlan plan = promoter.prepare(scan);
        scan.setDocumentVersionId(plan.versionId());
        return plan;
    }

    /** Persists and returns the row inserted (the last save() the repository saw). */
    private DocumentVersion persist(DocumentPromoter.PromotionPlan plan, ScanResult scan) {
        promoter.persist(plan, scan);
        ArgumentCaptor<DocumentVersion> saved = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versions, atLeastOnce()).save(saved.capture());
        List<DocumentVersion> all = new ArrayList<>(saved.getAllValues());
        DocumentVersion inserted = all.get(all.size() - 1);
        latest = inserted; // the next promotion sees it, as it would once this one committed
        return inserted;
    }

    @Test
    void aFirstVersion_isNumberedOneWithLabelOneZero_asBefore() {
        ScanResult scan = ScanResult.builder().scanResultId(UUID.randomUUID()).tenantId(tenantId)
                .status(ScanResultStatus.clean).ingestionKind(IngestionKind.new_document).documentTypeId(UUID.randomUUID())
                .title("new.docx").createdReason("initial_upload").originalFilename("new.docx")
                .mimeType("application/vnd.test").fileHash("ab").sizeBytes(3L).uploadedBy(uploader).build();
        DocumentPromoter.PromotionPlan plan = prepare(scan);

        DocumentVersion first = persist(plan, scan);

        assertThat(first.getVersionNumber()).isEqualTo(1);
        assertThat(first.getVersionLabel()).isEqualTo("1.0");
    }

    @Test
    void sequentialUploads_getConsecutiveNumbers_andLabels() {
        for (int expected = 2; expected <= 5; expected++) {
            ScanResult scan = scan("replacement_upload");
            DocumentVersion inserted = persist(prepare(scan), scan);
            assertThat(inserted.getVersionNumber()).isEqualTo(expected);
            assertThat(inserted.getVersionLabel()).isEqualTo("1." + (expected - 1));
        }
    }

    @Test
    void twoUploadsPreparedTogether_bothProposeTheSameNumber_butAreNumberedConsecutively() {
        ScanResult a = scan("replacement_upload");
        ScanResult b = scan("replacement_upload");
        DocumentPromoter.PromotionPlan planA = prepare(a);
        DocumentPromoter.PromotionPlan planB = prepare(b);
        assertThat(planA.versionNumber()).as("the hint").isEqualTo(2);
        assertThat(planB.versionNumber()).as("the same hint").isEqualTo(2);

        DocumentVersion first = persist(planA, a);
        DocumentVersion second = persist(planB, b); // persist now sees the first one as the newest row

        assertThat(first.getVersionNumber()).isEqualTo(2);
        assertThat(second.getVersionNumber()).as("not the stale hint").isEqualTo(3);
        assertThat(first.getVersionLabel()).isEqualTo("1.1");
        assertThat(second.getVersionLabel()).isEqualTo("1.2");
        // the object key keeps the hint it was prepared with; the row keeps the key it was prepared with
        assertThat(second.getStorageObjectKey()).isEqualTo(planB.objectKey()).contains("/v2/");
    }

    @Test
    void theNumberComesFromTheRowReadAtPersist_notFromThePlan() {
        ScanResult scan = scan("replacement_upload");
        DocumentPromoter.PromotionPlan plan = prepare(scan);         // hint 2 (newest row was 1)
        latest = row(7, 3, 4);                                       // meanwhile other versions were promoted
        DocumentVersion inserted = persist(plan, scan);

        assertThat(inserted.getVersionNumber()).isEqualTo(8);
        assertThat(inserted.getMajorVersion()).isEqualTo(3);
        assertThat(inserted.getMinorVersion()).isEqualTo(5);
        assertThat(inserted.getVersionLabel()).isEqualTo("3.5");
    }

    @Test
    void restoreAndSigningGeneratedVersions_areNumberedLikeAnyOther() {
        ScanResult restore = scan("rollback");
        DocumentVersion restored = persist(prepare(restore), restore);
        assertThat(restored.getVersionNumber()).isEqualTo(2);
        assertThat(restored.getCreatedReason()).isEqualTo("rollback");

        ScanResult signed = scan("signed_copy");
        DocumentVersion stamped = persist(prepare(signed), signed);
        assertThat(stamped.getVersionNumber()).isEqualTo(3);
        assertThat(stamped.getCreatedReason()).isEqualTo("signed_copy");
    }

    @Test
    void theLabelAndTheNumber_comeFromTheSameRow_andAreSetOnce() {
        latest = row(4, 2, 9);
        ScanResult scan = scan("replacement_upload");
        DocumentVersion inserted = persist(prepare(scan), scan);
        assertThat(inserted.getVersionNumber()).isEqualTo(5);
        assertThat(inserted.getMajorVersion()).isEqualTo(2);
        assertThat(inserted.getMinorVersion()).isEqualTo(10);
        assertThat(inserted.getVersionLabel()).isEqualTo("2.10");
    }
}
