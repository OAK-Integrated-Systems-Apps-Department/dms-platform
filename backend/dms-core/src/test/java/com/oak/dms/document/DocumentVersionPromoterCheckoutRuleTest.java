package com.oak.dms.document;

import com.oak.dms.checkout.SignedContentGuard;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.PromotionRefusedException;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.storage.entity.StorageLocation;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC-07: the check-out rule in {@code DocumentVersionPromoter}, case by case, with the real promoter over stubbed
 * repositories. A lease is identified by who holds it and when it was taken; the upload by the uploader and the time
 * the gate accepted it. Every row of the matrix: the original lease still active, expired with nobody taking over,
 * another user taking over, the same user taking a NEW lease, a force-release, an upload that predates a later
 * check-out, a signing-generated copy, and the DOC-06 rules that must still come first.
 */
class DocumentVersionPromoterCheckoutRuleTest {

    private static final String UPLOAD = "replacement_upload";

    private final UUID tenantId = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();   // the uploader
    private final UUID bob = UUID.randomUUID();     // somebody else
    private final UUID documentId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.now();
    /** The upload was accepted two hours ago and is being promoted only now. */
    private final OffsetDateTime submitted = now.minusHours(2);

    private Document document;
    private DocumentVersionPromoter promoter;

    @BeforeEach
    void wire() {
        UUID current = UUID.randomUUID();
        document = Document.builder().documentId(documentId).tenantId(tenantId).documentTypeId(UUID.randomUUID())
                .title("contract.docx").latestVersionId(current).currentVersionId(current).ownerUserId(alice).build();
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.lockByDocumentIdAndTenantId(documentId, tenantId)).thenAnswer(i -> Optional.of(document));
        when(documents.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenAnswer(i -> Optional.of(document));
        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        when(versions.findTopByDocumentIdOrderByVersionNumberDesc(documentId)).thenReturn(Optional.of(
                DocumentVersion.builder().versionId(current).documentId(documentId).tenantId(tenantId).versionNumber(1)
                        .majorVersion(1).minorVersion(0).build()));
        com.oak.dms.storage.service.StorageLocationResolver resolver =
                mock(com.oak.dms.storage.service.StorageLocationResolver.class);
        when(resolver.resolveForTenant(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(StorageLocation.builder().storageLocationId(UUID.randomUUID()).build());
        promoter = new DocumentVersionPromoter(documents, versions, resolver,
                mock(com.oak.dms.ocrworker.OcrEnqueueService.class), new com.oak.dms.ocrworker.OcrProperties(),
                mock(ApplicationEventPublisher.class), mock(com.oak.dms.user.UserRepository.class),
                mock(SignedContentGuard.class), mock(EntityManager.class));
    }

    private ScanResult scan(String reason, UUID uploader, OffsetDateTime startedAt) {
        UUID version = UUID.randomUUID();
        return ScanResult.builder().scanResultId(UUID.randomUUID()).tenantId(tenantId).status(ScanResultStatus.clean)
                .documentVersionId(version).ingestionKind(IngestionKind.new_version).targetDocumentId(documentId)
                .createdReason(reason).originalFilename("x.docx").mimeType("application/vnd.test").fileHash("ab")
                .sizeBytes(3L).uploadedBy(uploader).startedAt(startedAt).build();
    }

    private void checkedOut(UUID holder, OffsetDateTime taken, OffsetDateTime expires) {
        document.setCheckedOutBy(holder);
        document.setCheckedOutAt(taken);
        document.setCheckoutExpiresAt(expires);
    }

    /** Runs prepare (the early, unlocked look) and persist (the authoritative one, under the lock). */
    private void promote(ScanResult scan) {
        DocumentPromoter.PromotionPlan plan = promoter.prepare(scan);
        scan.setDocumentVersionId(plan.versionId());
        promoter.persist(plan, scan);
    }

    private void assertAllowed(ScanResult scan) {
        UUID before = document.getCurrentVersionId();
        promote(scan);
        assertThat(document.getCurrentVersionId()).isEqualTo(scan.getDocumentVersionId()).isNotEqualTo(before);
    }

    private void assertRefused(ScanResult scan, String code) {
        UUID before = document.getCurrentVersionId();
        assertThatThrownBy(() -> promote(scan)).isInstanceOfSatisfying(PromotionRefusedException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
        assertThat(document.getCurrentVersionId()).as("nothing was promoted").isEqualTo(before);
    }

    // ------------------------------------------------------------------ the original lease

    @Test
    void noCheckOut_anOrdinaryUploadPromotes() {
        assertAllowed(scan(UPLOAD, alice, submitted));
    }

    @Test
    void theHoldersOwnLease_takenBeforeTheFileWasSubmitted_andStillActive_promotes() {
        checkedOut(alice, submitted.minusHours(1), now.plusHours(70)); // a valid pending check-in
        assertAllowed(scan(UPLOAD, alice, submitted));
    }

    @Test
    void anotherUserHoldsAnActiveCheckOut_anOrdinaryUploadIsRefused() {
        checkedOut(bob, submitted.minusHours(1), now.plusHours(70));
        assertRefused(scan(UPLOAD, alice, submitted), "DOCUMENT_CHECKED_OUT");
    }

    // ------------------------------------------------------------------ pending upload, then a check-out

    @Test
    void aPendingUpload_thenSomeoneElseChecksOut_isRefused() {
        checkedOut(bob, submitted.plusMinutes(30), now.plusHours(70)); // taken after the file was accepted
        assertRefused(scan(UPLOAD, alice, submitted), "DOCUMENT_CHECKED_OUT");
    }

    @Test
    void aPendingUpload_thenTheUploaderChecksOutAgain_isRefused_aNewLeaseIsNotTheOneItWasSubmittedUnder() {
        checkedOut(alice, submitted.plusMinutes(30), now.plusHours(70));
        assertRefused(scan(UPLOAD, alice, submitted), "DOCUMENT_CHECKED_OUT");
    }

    // ------------------------------------------------------------------ expiry, takeover, force release

    @Test
    void theOriginalLeaseExpired_withNobodyTakingOver_theStaleCheckInIsRefused() {
        checkedOut(alice, submitted.minusHours(1), now.minusMinutes(5)); // still on the row, expired
        assertRefused(scan(UPLOAD, alice, submitted), "CHECKOUT_EXPIRED");
    }

    @Test
    void anotherUserTookOverAfterExpiry_theEarlierCheckInCannotOverwriteThem() {
        checkedOut(bob, now.minusMinutes(20), now.plusHours(72)); // takeover replaced the row
        assertRefused(scan(UPLOAD, alice, submitted), "DOCUMENT_CHECKED_OUT");
    }

    @Test
    void theSameUserTookANewLeaseAfterExpiry_theOldCheckInCannotUseIt() {
        checkedOut(alice, now.minusMinutes(20), now.plusHours(72)); // a second acquisition, after the upload
        assertRefused(scan(UPLOAD, alice, submitted), "DOCUMENT_CHECKED_OUT");
    }

    @Test
    void anExpiredLeaseOfSomeoneElse_blocksNobody() {
        checkedOut(bob, submitted.minusHours(5), now.minusHours(1)); // expired, never taken over
        assertAllowed(scan(UPLOAD, alice, submitted));
    }

    @Test
    void aForceReleasedCheckOut_leavesNoTrace_soThePromotionProceedsLikeAnyUpload_documentedLimitation() {
        // releaseCheckoutAnyHolder clears every column: "force-released" cannot be told from "never checked out".
        assertAllowed(scan(UPLOAD, alice, submitted));
    }

    // ------------------------------------------------------------------ fail closed, and the exemption

    @Test
    void aMissingSubmissionTime_isNotProvablyBeforeTheLease_andFailsClosed() {
        checkedOut(alice, submitted.minusHours(1), now.plusHours(70));
        assertRefused(scan(UPLOAD, alice, null), "DOCUMENT_CHECKED_OUT");
    }

    @Test
    void signingGeneratedCopies_areExempt_whoeverHoldsTheCheckOut() {
        checkedOut(bob, submitted.minusHours(1), now.plusHours(70));
        assertAllowed(scan("signed_copy", alice, submitted));
    }

    @Test
    void restoreAndEditorSaves_areJudgedByTheSameLeaseRule() {
        checkedOut(alice, submitted.minusHours(1), now.plusHours(70));
        assertAllowed(scan("rollback", alice, submitted));
        checkedOut(bob, submitted.minusHours(1), now.plusHours(70));
        assertRefused(scan("rollback", alice, submitted), "DOCUMENT_CHECKED_OUT");
    }

    // ------------------------------------------------------------------ DOC-06 still comes first

    @Test
    void theDoc06RulesAreStillEnforced_andAnswerBeforeTheCheckOutRule() {
        checkedOut(bob, submitted.minusHours(1), now.plusHours(70));
        document.setLegalHold(true);
        assertRefused(scan(UPLOAD, alice, submitted), "DOCUMENT_LEGAL_HOLD");
        document.setLegalHold(false);
        document.setSignatureLockedAt(now);
        assertRefused(scan(UPLOAD, alice, submitted), "DOCUMENT_SIGNATURE_LOCKED");
    }
}
