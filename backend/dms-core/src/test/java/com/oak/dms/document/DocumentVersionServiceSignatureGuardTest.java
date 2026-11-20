package com.oak.dms.document;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.checkout.CheckoutException;
import com.oak.dms.checkout.DocumentEditGuard;
import com.oak.dms.checkout.SignedContentGuard;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import com.oak.dms.signature.service.DocumentSignatureLockService;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DOC-05 at unit level: the real {@link SignedContentGuard} (over stubbed repositories) inside the real
 * {@link DocumentVersionService}. Covers the one signature kind the integration test cannot seed cheaply - a VALID
 * signature record bound to the current version, which is what standalone signing writes - and pins the ORDER of
 * the checks: the workflow lock and the legal-hold/check-out guard answer first, the signature guard runs before the
 * ingestion gate, and nothing is read, scanned or audited when it refuses.
 */
class DocumentVersionServiceSignatureGuardTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID oldVersionId = UUID.randomUUID();
    private final UUID currentVersionId = UUID.randomUUID();

    private DocumentRepository documents;
    private DocumentVersionRepository versions;
    private DocumentSignatureRepository signatures;
    private IngestionGate gate;
    private AuditService audit;
    private Object versionContent;
    private Document document;

    @BeforeEach
    void bind() {
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    /** The service wired with the real guards unless a test passes its own signed-content guard. */
    private DocumentVersionService service(SignedContentGuard signedGuard) throws Exception {
        document = Document.builder().documentId(documentId).tenantId(tenantId).documentTypeId(UUID.randomUUID())
                .title("contract.pdf").latestVersionId(currentVersionId).currentVersionId(currentVersionId)
                .ownerUserId(userId).build();
        documents = mock(DocumentRepository.class);
        when(documents.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)).thenReturn(Optional.of(document));
        versions = mock(DocumentVersionRepository.class);
        DocumentVersion old = DocumentVersion.builder().versionId(oldVersionId).documentId(documentId).tenantId(tenantId)
                .originalFilename("contract.pdf").sizeBytes(3).mimeType("application/pdf").build();
        when(versions.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(oldVersionId, documentId, tenantId))
                .thenReturn(Optional.of(old));
        signatures = mock(DocumentSignatureRepository.class);
        DocumentAccessService access = mock(DocumentAccessService.class);
        when(access.require(any(), any(), any())).thenAnswer(i -> i.getArgument(1));
        gate = mock(IngestionGate.class);
        IngestionTicketResponse ticket = mock(IngestionTicketResponse.class);
        when(ticket.scanResultId()).thenReturn(UUID.randomUUID());
        when(gate.submit(any(IngestionRequest.class))).thenReturn(ticket);
        audit = mock(AuditService.class, Mockito.RETURNS_DEEP_STUBS);
        versionContent = mock(Class.forName("com.oak.dms.document.VersionContent"), inv ->
                inv.getMethod().getReturnType() == DownloadedDocument.class
                        ? new DownloadedDocument(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "application/pdf", "contract.pdf")
                        : Mockito.RETURNS_DEFAULTS.answer(inv));
        DocumentSignatureLockService lock = new DocumentSignatureLockService(documents, signatures, mock(AuditService.class));
        DocumentEditGuard editGuard = new DocumentEditGuard(mock(UserRepository.class), documents);
        return new DocumentVersionService(documents, versions, access, gate,
                (VersionContent) versionContent, mock(UserRepository.class), audit, lock, editGuard,
                signedGuard != null ? signedGuard : new SignedContentGuard(signatures, versions, unsignableFileReader()),
                mock(DefaultDownloadWatermark.class));
    }

    /** A reader whose current file is a plain .txt: a type that cannot hold a signature, so the guard stops at the records. */
    private DocumentContentReader unsignableFileReader() {
        DocumentContentReader reader = mock(DocumentContentReader.class);
        DocumentContentReader.EditableVersion file = mock(DocumentContentReader.EditableVersion.class);
        when(file.filename()).thenReturn("notes.txt");
        when(reader.currentForEditing(any(), any())).thenReturn(file);
        return reader;
    }

    private void currentVersionHasAValidSignatureRecord() {
        DocumentSignature signature = mock(DocumentSignature.class);
        when(signature.isValid()).thenReturn(true);
        when(signature.getVersionId()).thenReturn(currentVersionId);
        when(signatures.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId)).thenReturn(List.of(signature));
    }

    private static InputStreamHolder bytes() {
        return new InputStreamHolder();
    }

    private static final class InputStreamHolder {
        ByteArrayInputStream stream() {
            return new ByteArrayInputStream(new byte[]{1, 2, 3});
        }
    }

    @Test
    void aValidSignatureRecordOnTheCurrentVersion_refusesUploadAndRestore_beforeTheGateOrAnyRead() throws Exception {
        DocumentVersionService service = service(null);
        currentVersionHasAValidSignatureRecord();

        assertThatThrownBy(() -> service.upload(documentId, "x.pdf", 3, bytes().stream(), "c"))
                .isInstanceOfSatisfying(CheckoutException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(SignedContentGuard.CODE);
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
        assertThatThrownBy(() -> service.restore(documentId, oldVersionId))
                .isInstanceOfSatisfying(CheckoutException.class, e -> assertThat(e.getCode()).isEqualTo(SignedContentGuard.CODE));

        verifyNoInteractions(gate);
        verifyNoInteractions(versionContent); // restore never opened the old version's file
        verifyNoInteractions(audit);          // no DOCUMENT_VERSION_RESTORED written
    }

    @Test
    void aValidSignatureOnAnOLDERVersionOnly_doesNotBlock_itIsAboutTheCurrentOne() throws Exception {
        DocumentVersionService service = service(null);
        DocumentSignature oldSignature = mock(DocumentSignature.class);
        when(oldSignature.isValid()).thenReturn(true);
        when(oldSignature.getVersionId()).thenReturn(oldVersionId);
        when(signatures.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId)).thenReturn(List.of(oldSignature));

        service.upload(documentId, "x.pdf", 3, bytes().stream(), "c");
        service.restore(documentId, oldVersionId);

        verify(gate, Mockito.times(2)).submit(any(IngestionRequest.class));
    }

    @Test
    void anInvalidatedSignatureRecord_doesNotBlock() throws Exception {
        DocumentVersionService service = service(null);
        DocumentSignature signature = mock(DocumentSignature.class);
        when(signature.isValid()).thenReturn(false);
        when(signature.getVersionId()).thenReturn(currentVersionId);
        when(signatures.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId)).thenReturn(List.of(signature));

        service.upload(documentId, "x.pdf", 3, bytes().stream(), "c");
        verify(gate).submit(any(IngestionRequest.class));
    }

    @Test
    void aSignedCopyMark_refusesToo() throws Exception {
        DocumentVersionService service = service(null);
        DocumentVersion current = DocumentVersion.builder().versionId(currentVersionId).documentId(documentId)
                .tenantId(tenantId).createdReason("signed_copy").build();
        when(versions.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(currentVersionId, documentId, tenantId))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.upload(documentId, "x.pdf", 3, bytes().stream(), "c"))
                .isInstanceOfSatisfying(CheckoutException.class, e -> assertThat(e.getCode()).isEqualTo(SignedContentGuard.CODE));
        verifyNoInteractions(gate);
    }

    @Test
    void anUnsignedDocument_isAllowed_andTheGuardRunsBeforeTheGate() throws Exception {
        SignedContentGuard guard = mock(SignedContentGuard.class);
        DocumentVersionService service = service(guard);

        service.upload(documentId, "x.pdf", 3, bytes().stream(), "c");
        service.restore(documentId, oldVersionId);

        InOrder order = inOrder(guard, gate);
        order.verify(guard).guardOpening(document);
        order.verify(gate).submit(any(IngestionRequest.class));
        order.verify(guard).guardOpening(document);
        order.verify(gate).submit(any(IngestionRequest.class));
    }

    @Test
    void theWorkflowLockAnswersFirst_andKeepsItsOwnError() throws Exception {
        SignedContentGuard guard = mock(SignedContentGuard.class);
        DocumentVersionService service = service(guard);
        document.setSignatureLockedAt(OffsetDateTime.now());

        assertThatThrownBy(() -> service.upload(documentId, "x.pdf", 3, bytes().stream(), "c"))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_SIGNATURE_LOCKED"));
        assertThatThrownBy(() -> service.restore(documentId, oldVersionId))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_SIGNATURE_LOCKED"));
        verify(guard, never()).guardOpening(any());
        verifyNoInteractions(gate);
    }

    @Test
    void aLegalHold_answersBeforeTheSignatureGuard_withItsOwnError() throws Exception {
        SignedContentGuard guard = mock(SignedContentGuard.class);
        DocumentVersionService service = service(guard);
        document.setLegalHold(true);

        assertThatThrownBy(() -> service.upload(documentId, "x.pdf", 3, bytes().stream(), "c"))
                .isInstanceOfSatisfying(CheckoutException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_LEGAL_HOLD"));
        verify(guard, never()).guardOpening(any());
        verifyNoInteractions(gate);
    }
}
