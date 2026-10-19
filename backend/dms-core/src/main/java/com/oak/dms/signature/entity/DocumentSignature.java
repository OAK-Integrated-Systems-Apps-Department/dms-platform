package com.oak.dms.signature.entity;

import com.oak.dms.signature.enums.SignatureMeaning;
import com.oak.dms.signature.enums.SignatureStatus;
import com.oak.dms.signature.enums.VerificationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One signature (V108) - append-only evidence, enforced in the database by
 * {@code trg_document_signatures_append_only}: every field below except
 * {@link #status}, {@link #statusChangedAt}, {@link #statusReason},
 * {@link #lastVerifiedAt} and {@link #lastVerificationResult} is immutable
 * after insert, and the row itself can never be deleted. This entity mirrors
 * that split - every setter exists (Lombok {@code @Setter} on the whole
 * class, matching the rest of this codebase's entities), but
 * {@code DocumentSignatureService}/{@code WorkflowSignatureListener} are the
 * only callers that ever call one, and only for the five mutable fields; a
 * write to any other field would simply fail at the database regardless.
 * <p>
 * {@link #manifestText} is the EXACT canonical JSON text that was signed -
 * verification replays this text byte for byte, never a re-serialization of
 * it (decision 3). {@link #publicKeyPem} is copied from the signing key at
 * the moment of signing, so evidence stays independently verifiable even if
 * the key is later revoked - revocation never touches this row.
 */
@Entity
@Table(name = "document_signatures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentSignature {

    @Id
    @Column(name = "signature_id", updatable = false, nullable = false)
    private UUID signatureId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "workflow_instance_id", nullable = false, updatable = false)
    private UUID workflowInstanceId;

    @Column(name = "task_id", updatable = false)
    private UUID taskId;

    @Column(name = "signer_user_id", nullable = false, updatable = false)
    private UUID signerUserId;

    @Column(name = "signer_name", nullable = false, updatable = false)
    private String signerName;

    @Column(name = "signer_email", nullable = false, updatable = false)
    private String signerEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "meaning", nullable = false, updatable = false)
    private SignatureMeaning meaning;

    @Column(name = "comment_text", updatable = false)
    private String commentText;

    @Column(name = "manifest_text", nullable = false, updatable = false)
    private String manifestText;

    @Column(name = "signature_der_base64", nullable = false, updatable = false)
    private String signatureDerBase64;

    @Column(name = "public_key_pem", nullable = false, updatable = false)
    private String publicKeyPem;

    @Column(name = "signing_key_id", nullable = false, updatable = false)
    private UUID signingKeyId;

    @Column(name = "signing_key_name", nullable = false, updatable = false)
    private String signingKeyName;

    @Column(name = "algorithm", nullable = false, updatable = false)
    @Builder.Default
    private String algorithm = "ECDSA_P256_SHA256";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private SignatureStatus status = SignatureStatus.valid;

    @Column(name = "status_changed_at")
    private OffsetDateTime statusChangedAt;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "last_verified_at")
    private OffsetDateTime lastVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_verification_result")
    private VerificationResult lastVerificationResult;

    @Column(name = "signed_at", nullable = false, updatable = false)
    private OffsetDateTime signedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public boolean isValid() {
        return status == SignatureStatus.valid;
    }
}
