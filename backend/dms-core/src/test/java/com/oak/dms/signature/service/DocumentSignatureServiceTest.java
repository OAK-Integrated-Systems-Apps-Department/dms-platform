package com.oak.dms.signature.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.SignAndApproveRequest;
import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.entity.UserSigningKey;
import com.oak.dms.signature.enums.SignatureMeaning;
import com.oak.dms.signature.enums.VisualSignatureKind;
import com.oak.dms.signature.event.SignatureRejectedEvent;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.signature.helper.PemKeys;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.oak.dms.signature.dto.VerifyAllResponse;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The core fail-closed guarantees: a hash mismatch refuses before anything
 * is signed, a successful sign records a valid, self-verified signature,
 * and a failed self-check records nothing - matching decisions 1/3/5.
 */
@ExtendWith(MockitoExtension.class)
class DocumentSignatureServiceTest {

    @Mock private DocumentSignatureRepository signatureRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVersionRepository versionRepository;
    @Mock private DocumentContentReader contentReader;
    @Mock private UserRepository userRepository;
    @Mock private SigningKeyService signingKeyService;
    @Mock private UserVisualSignatureService visualSignatureService;
    @Mock private DocumentSigner signer;
    @Mock private StepUpAuthenticator stepUp;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WorkflowInstanceRepository instanceRepository;

    private DocumentSignatureService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private static final byte[] DOCUMENT_BYTES = "the stored document bytes".getBytes();

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        service = new DocumentSignatureService(signatureRepository, documentRepository, versionRepository,
                contentReader, userRepository, signingKeyService, visualSignatureService, signer,
                new SignatureManifestBuilder(mapper), new SignatureVerifier(), stepUp, auditService, eventPublisher,
                mapper, instanceRepository);
        TenantContext.set(tenantId, userId);
        lenient().when(auditService.event(anyString()))
                .thenReturn(new com.oak.dms.audit.helper.AuditEventBuilder(auditService, "x"));
        lenient().when(userRepository.findById(userId))
                .thenReturn(Optional.of(User.builder().userId(userId).displayName("Sign Er").email("signer@test.example").build()));
        lenient().when(visualSignatureService.validateTypedText(anyString())).thenReturn("Sign Er");
        lenient().when(contentReader.open(documentId, versionId))
                .thenAnswer(inv -> new DownloadedDocument(new ByteArrayInputStream(DOCUMENT_BYTES), DOCUMENT_BYTES.length, "application/pdf", "doc.pdf"));
        // A real JpaRepository.save/saveAndFlush returns its argument - the bare mock would return null.
        lenient().when(signatureRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(signatureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private WorkflowInstance instance() {
        return WorkflowInstance.builder().instanceId(instanceId).tenantId(tenantId).documentId(documentId)
                .documentVersionId(versionId).initiatedBy(UUID.randomUUID()).build();
    }

    private WorkflowTask task() {
        return WorkflowTask.builder().taskId(taskId).build();
    }

    private SignAndApproveRequest request(boolean consent) {
        return new SignAndApproveRequest("a comment", SignatureMeaning.approved, consent, "password", null,
                VisualSignatureKind.typed, "Sign Er", null);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private DocumentVersion version(String fileHash) {
        return DocumentVersion.builder().versionId(versionId).documentId(documentId).tenantId(tenantId)
                .versionNumber(1).originalFilename("doc.pdf").mimeType("application/pdf").fileHash(fileHash)
                .sizeBytes(DOCUMENT_BYTES.length).build();
    }

    @Test
    void refusesWithoutConsentAndPublishesRejectedEvent() {
        assertThatThrownBy(() -> service.sign(instance(), task(), request(false)))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SIGNATURE_CONSENT_REQUIRED"));

        verify(signatureRepository, never()).saveAndFlush(any());
        ArgumentCaptor<SignatureRejectedEvent> captor = ArgumentCaptor.forClass(SignatureRejectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("SIGNATURE_CONSENT_REQUIRED");
        assertThat(captor.getValue().taskId()).isEqualTo(taskId);
    }

    @Test
    void refusesADuplicateTaskBeforeStepUp() {
        when(signatureRepository.existsByTenantIdAndTaskId(tenantId, taskId)).thenReturn(true);

        assertThatThrownBy(() -> service.sign(instance(), task(), request(true)))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SIGNATURE_ALREADY_RECORDED"));

        verify(stepUp, never()).verify(any(), any(), any());
    }

    @Test
    void refusesWhenTheStoredFileNoLongerMatchesItsRecordedHash() throws Exception {
        when(versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, tenantId))
                .thenReturn(Optional.of(version("0000000000000000000000000000000000000000000000000000000000000000")));

        assertThatThrownBy(() -> service.sign(instance(), task(), request(true)))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SIGNATURE_DOCUMENT_INTEGRITY"));

        verify(signer, never()).sign(any(), any());
        verify(signatureRepository, never()).saveAndFlush(any());
        ArgumentCaptor<SignatureRejectedEvent> captor = ArgumentCaptor.forClass(SignatureRejectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("SIGNATURE_DOCUMENT_INTEGRITY");
    }

    @Test
    void recordsAValidSignatureOnSuccess() throws Exception {
        when(versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, tenantId))
                .thenReturn(Optional.of(version(sha256Hex(DOCUMENT_BYTES))));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String publicKeyPem = PemKeys.encodePublicKey(pair.getPublic());
        UserSigningKey key = UserSigningKey.builder().keyId(UUID.randomUUID()).keyName("dms-sig-test")
                .publicKeyPem(publicKeyPem).build();
        when(signingKeyService.ensureActiveKey(tenantId, userId)).thenReturn(key);
        when(signer.sign(eq("dms-sig-test"), any())).thenAnswer(inv -> {
            byte[] digest = inv.getArgument(1);
            Signature s = Signature.getInstance("NONEwithECDSA");
            s.initSign(pair.getPrivate());
            s.update(digest);
            return s.sign();
        });

        DocumentSignature saved = service.sign(instance(), task(), request(true));

        assertThat(saved.getStatus().name()).isEqualTo("valid");
        assertThat(saved.getDocumentId()).isEqualTo(documentId);
        assertThat(saved.getVersionId()).isEqualTo(versionId);
        assertThat(saved.getTaskId()).isEqualTo(taskId);
        verify(signatureRepository).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void recordsNothingWhenTheSelfCheckFails() throws Exception {
        when(versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, tenantId))
                .thenReturn(Optional.of(version(sha256Hex(DOCUMENT_BYTES))));
        UserSigningKey key = UserSigningKey.builder().keyId(UUID.randomUUID()).keyName("dms-sig-test")
                .publicKeyPem(PemKeys.encodePublicKey(freshKeyPair().getPublic())).build();
        when(signingKeyService.ensureActiveKey(tenantId, userId)).thenReturn(key);
        // Signed with an UNRELATED key - will never verify against the stored public key.
        when(signer.sign(any(), any())).thenAnswer(inv -> {
            byte[] random = new byte[70];
            new SecureRandom().nextBytes(random);
            random[0] = 0x30; // a plausible-looking DER SEQUENCE tag, still garbage
            return random;
        });

        assertThatThrownBy(() -> service.sign(instance(), task(), request(true)))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SIGNATURE_SELF_CHECK_FAILED"));

        verify(signatureRepository, never()).saveAndFlush(any());
        ArgumentCaptor<SignatureRejectedEvent> captor = ArgumentCaptor.forClass(SignatureRejectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("SIGNATURE_SELF_CHECK_FAILED");
    }

    private static KeyPair freshKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    // -------------------------------------------- altered-after-signing (verify side)

    private DocumentSignature signSuccessfully() throws Exception {
        when(versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, tenantId))
                .thenReturn(Optional.of(version(sha256Hex(DOCUMENT_BYTES))));
        KeyPair pair = freshKeyPair();
        UserSigningKey key = UserSigningKey.builder().keyId(UUID.randomUUID()).keyName("dms-sig-test")
                .publicKeyPem(PemKeys.encodePublicKey(pair.getPublic())).build();
        when(signingKeyService.ensureActiveKey(tenantId, userId)).thenReturn(key);
        when(signer.sign(eq("dms-sig-test"), any())).thenAnswer(inv -> {
            byte[] digest = inv.getArgument(1);
            Signature s = Signature.getInstance("NONEwithECDSA");
            s.initSign(pair.getPrivate());
            s.update(digest);
            return s.sign();
        });
        return service.sign(instance(), task(), request(true));
    }

    @Test
    void verifyAllFlagsADocumentAlteredAfterSigning() throws Exception {
        DocumentSignature signed = signSuccessfully();

        // The stored file has since changed - verification re-reads it fresh.
        byte[] alteredBytes = "an altered document".getBytes();
        when(contentReader.open(documentId, versionId)).thenAnswer(inv ->
                new DownloadedDocument(new ByteArrayInputStream(alteredBytes), alteredBytes.length, "application/pdf", "doc.pdf"));
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(Document.builder().documentId(documentId).tenantId(tenantId)
                        .currentVersionId(versionId).title("Doc").build()));
        when(signatureRepository.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId))
                .thenReturn(List.of(signed));

        VerifyAllResponse result = service.verifyAll(documentId);

        assertThat(result.invalidated()).isEqualTo(1);
        assertThat(result.valid()).isEqualTo(0);
        assertThat(signed.getStatus().name()).isEqualTo("invalidated");
        assertThat(signed.getLastVerificationResult().name()).isEqualTo("document_altered");
    }

    @Test
    void verifyAllConfirmsAnUnalteredDocumentStaysValid() throws Exception {
        DocumentSignature signed = signSuccessfully();

        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(Document.builder().documentId(documentId).tenantId(tenantId)
                        .currentVersionId(versionId).title("Doc").build()));
        when(signatureRepository.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId))
                .thenReturn(List.of(signed));

        VerifyAllResponse result = service.verifyAll(documentId);

        assertThat(result.valid()).isEqualTo(1);
        assertThat(result.invalidated()).isEqualTo(0);
        assertThat(signed.getStatus().name()).isEqualTo("valid");
        assertThat(signed.getLastVerificationResult().name()).isEqualTo("valid");
    }
}
