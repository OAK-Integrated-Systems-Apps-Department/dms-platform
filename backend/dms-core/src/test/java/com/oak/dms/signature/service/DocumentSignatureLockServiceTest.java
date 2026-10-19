package com.oak.dms.signature.service;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.enums.SignatureStatus;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSignatureLockServiceTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentSignatureRepository signatureRepository;
    @Mock
    private AuditService auditService;

    private DocumentSignatureLockService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DocumentSignatureLockService(documentRepository, signatureRepository, auditService);
        TenantContext.set(tenantId, UUID.randomUUID());
        lenient().when(auditService.event(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.oak.dms.audit.helper.AuditEventBuilder(auditService, "x"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Document document(OffsetDateTime lockedAt) {
        return Document.builder().documentId(documentId).tenantId(tenantId).title("Doc")
                .signatureLockedAt(lockedAt).signatureLockedVersionId(lockedAt == null ? null : versionId).build();
    }

    @Test
    void guardAllowsAnUnlockedDocument() {
        service.guard(document(null));
        // no exception
    }

    @Test
    void guardRefusesALockedDocument() {
        assertThatThrownBy(() -> service.guard(document(OffsetDateTime.now())))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_SIGNATURE_LOCKED"));
    }

    @Test
    void lockIsANoOpWhenAlreadyLocked() {
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(document(OffsetDateTime.now())));

        service.lock(tenantId, documentId, versionId);

        verify(documentRepository, never()).save(any());
    }

    @Test
    void lockIsANoOpWhenNoValidSignatureExistsForTheVersion() {
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(document(null)));
        when(signatureRepository.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId))
                .thenReturn(List.of());

        service.lock(tenantId, documentId, versionId);

        verify(documentRepository, never()).save(any());
    }

    @Test
    void locksWhenAValidSignatureExistsForTheVersion() {
        Document unlocked = document(null);
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(unlocked));
        DocumentSignature valid = DocumentSignature.builder().signatureId(UUID.randomUUID())
                .versionId(versionId).status(SignatureStatus.valid).build();
        when(signatureRepository.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId))
                .thenReturn(List.of(valid));

        service.lock(tenantId, documentId, versionId);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getSignatureLockedAt()).isNotNull();
        assertThat(captor.getValue().getSignatureLockedVersionId()).isEqualTo(versionId);
    }

    @Test
    void doesNotLockForAnInvalidatedSignature() {
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(document(null)));
        DocumentSignature invalidated = DocumentSignature.builder().signatureId(UUID.randomUUID())
                .versionId(versionId).status(SignatureStatus.invalidated).build();
        when(signatureRepository.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId))
                .thenReturn(List.of(invalidated));

        service.lock(tenantId, documentId, versionId);

        verify(documentRepository, never()).save(any());
    }

    @Test
    void unlockRequiresTheDocumentToBeLocked() {
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(document(null)));

        assertThatThrownBy(() -> service.unlock(documentId, "needed for X", UUID.randomUUID()))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_NOT_SIGNATURE_LOCKED"));
    }

    @Test
    void unlockClearsBothLockColumnsAndDoesNotTouchSignatures() {
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(document(OffsetDateTime.now())));

        service.unlock(documentId, "needed for X", UUID.randomUUID());

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getSignatureLockedAt()).isNull();
        assertThat(captor.getValue().getSignatureLockedVersionId()).isNull();
        verify(signatureRepository, never()).save(any());
        verify(signatureRepository, never()).saveAll(any());
    }
}
