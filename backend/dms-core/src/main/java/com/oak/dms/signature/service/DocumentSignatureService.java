package com.oak.dms.signature.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.DocumentSignatureResponse;
import com.oak.dms.signature.dto.SignAndApproveRequest;
import com.oak.dms.signature.dto.SignatureEvidenceResponse;
import com.oak.dms.signature.dto.VerifyAllResponse;
import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.entity.UserSigningKey;
import com.oak.dms.signature.enums.SignatureStatus;
import com.oak.dms.signature.enums.VerificationResult;
import com.oak.dms.signature.enums.VisualSignatureKind;
import com.oak.dms.signature.event.SignatureRejectedEvent;
import com.oak.dms.signature.event.SignatureVerificationFailedEvent;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The heart of 1.4 Digital Signature Integration: records one signature
 * (fail-closed - see {@link #sign}) and re-verifies existing ones
 * (decision 5). Never moves or rewrites a signature's evidence columns -
 * only the five mutable ones the database itself allows
 * ({@code trg_document_signatures_append_only}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSignatureService {

    private final DocumentSignatureRepository signatureRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentContentReader contentReader;
    private final UserRepository userRepository;
    private final SigningKeyService signingKeyService;
    private final UserVisualSignatureService visualSignatureService;
    private final DocumentSigner signer;
    private final SignatureManifestBuilder manifestBuilder;
    private final SignatureVerifier verifier;
    private final StepUpAuthenticator stepUp;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper mapper;
    private final com.oak.dms.workflow.repository.WorkflowInstanceRepository instanceRepository;

    // ------------------------------------------------------------- signing

    /**
     * Signs the workflow instance's SUBMITTED version ({@code instance.documentVersionId}
     * - decision 3, never the document's latest) in the acting user's own
     * name, inside the caller's existing transaction. Every failure path
     * below publishes a {@link SignatureRejectedEvent} before throwing, so
     * {@code SignatureFailureRecorder} can still audit it after this
     * transaction (and the approval it was about to produce) rolls back -
     * see that class's own doc comment for why a plain in-transaction audit
     * row would otherwise vanish with the rollback.
     *
     * @throws SignatureException 400 missing consent/password/OTP, 403 while
     *                             impersonating, 409 already signed or the
     *                             stored file no longer matches its recorded
     *                             hash, 503 the signing key service is
     *                             unreachable or the signature failed its
     *                             own immediate self-check
     */
    @Transactional
    public DocumentSignature sign(WorkflowInstance instance, WorkflowTask task, SignAndApproveRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID documentId = instance.getDocumentId();
        UUID taskId = task.getTaskId();
        // Only a VALID signature blocks a re-sign: after a retraction, invalidateForTaskRetraction
        // marks the old row invalidated rather than deleting it (append-only evidence), and this task
        // is legitimately re-decidable at that point - a blanket existence check here would wrongly
        // refuse to let the approver sign again just because their retracted attempt's row still exists.
        if (signatureRepository.existsByTenantIdAndTaskIdAndStatus(tenantId, taskId, SignatureStatus.valid)) {
            throw rejected(SignatureException.conflict("SIGNATURE_ALREADY_RECORDED",
                    "This task has already been signed."), tenantId, TenantContext.getUserId(), documentId, taskId);
        }
        return signInternal(documentId, instance.getDocumentVersionId(), instance.getInstanceId(), taskId, request);
    }

    /**
     * Signs a document directly (V119) - the current version as a standalone e-signature, not as part of
     * approving any workflow task. {@code request.meaning()} is still caller-supplied (the standalone
     * sign endpoint always sends {@code acknowledged}, never {@code approved} - there is no approval
     * here), everything else works exactly like {@link #sign} with no workflow instance or task behind
     * the resulting row.
     */
    @Transactional
    public DocumentSignature signStandalone(UUID documentId, SignAndApproveRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        // Refuses while a workflow approval is actively in progress - otherwise a signer unrelated to
        // that approval could add their own independent signature mid-approval. See
        // StandaloneSignaturePlacementService#guardNoActiveWorkflow's own doc comment for the full reasoning.
        if (instanceRepository.findFirstByTenantIdAndDocumentIdAndStatus(tenantId, documentId,
                com.oak.dms.workflow.enums.InstanceStatus.in_progress).isPresent()) {
            throw SignatureException.conflict("DOCUMENT_WORKFLOW_IN_PROGRESS",
                    "This document has an approval workflow in progress - standalone signing isn't available until it finishes.");
        }
        UUID versionId = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        return signInternal(documentId, versionId, null, null, request);
    }

    /**
     * Shared by {@link #sign} (a workflow instance/task behind it) and {@link #signStandalone} (neither) -
     * everything from here on treats a null {@code workflowInstanceId}/{@code taskId} exactly like a real
     * one, since nothing below actually branches on which path called it.
     */
    private DocumentSignature signInternal(UUID documentId, UUID versionId, UUID workflowInstanceId, UUID taskId,
                                           SignAndApproveRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actorUserId = TenantContext.getUserId();

        if (!request.consent()) {
            throw rejected(SignatureException.badRequest("SIGNATURE_CONSENT_REQUIRED",
                    "Confirm you intend to sign this document."), tenantId, actorUserId, documentId, taskId);
        }

        // Cheap, no-network validation before the Keycloak round trip below - a malformed visual
        // signature shouldn't cost the signer a wasted step-up attempt.
        String visualText;
        byte[] visualImage;
        try {
            // drawn/uploaded are stored and validated identically - see VisualSignatureKind's own doc comment.
            visualText = request.visualSignatureKind() == VisualSignatureKind.typed
                    ? visualSignatureService.validateTypedText(request.visualSignatureText()) : null;
            visualImage = request.visualSignatureKind() == VisualSignatureKind.typed
                    ? null : visualSignatureService.validateImage(request.visualSignatureImageBase64());
        } catch (SignatureException e) {
            throw rejected(e, tenantId, actorUserId, documentId, taskId);
        }

        User signerUser = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", actorUserId));
        try {
            stepUp.verify(signerUser, request.password(), request.totp());
        } catch (SignatureException e) {
            throw rejected(e, tenantId, actorUserId, documentId, taskId);
        }

        DocumentVersion version = versionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));

        byte[] original;
        try (InputStream content = contentReader.open(documentId, versionId).content()) {
            original = content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read document " + documentId + " for signing", e);
        }
        String actualHash = sha256Hex(original);
        if (!actualHash.equalsIgnoreCase(version.getFileHash())) {
            throw rejected(SignatureException.conflict("SIGNATURE_DOCUMENT_INTEGRITY",
                    "The stored file no longer matches its recorded hash - refusing to sign."),
                    tenantId, actorUserId, documentId, taskId);
        }

        UserSigningKey key = signingKeyService.ensureActiveKey(tenantId, actorUserId);

        String comment = clean(request.comment());
        OffsetDateTime signedAt = OffsetDateTime.now(ZoneOffset.UTC);
        String manifestText = manifestBuilder.build(new SignatureManifestBuilder.ManifestInput(
                tenantId, documentId, versionId, version.getVersionNumber(), actualHash, actorUserId,
                signerUser.getDisplayName(), signerUser.getEmail(), request.meaning(), comment,
                workflowInstanceId, taskId, signedAt));

        byte[] der;
        try {
            der = signer.sign(key.getKeyName(), MessageDigest.getInstance("SHA-256")
                    .digest(manifestText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JVM has no SHA-256 implementation", e);
        } catch (RuntimeException e) {
            log.warn("Could not reach the signing key service for {}: {}", key.getKeyName(), e.getMessage());
            throw rejected(SignatureException.unavailable("SIGNATURE_KEY_SERVICE_UNAVAILABLE",
                    "Could not reach the signing key service - try again."), tenantId, actorUserId, documentId, taskId);
        }
        String signatureDerBase64 = java.util.Base64.getEncoder().encodeToString(der);

        // DSI-FR-003: self-verify immediately - never record a signature that doesn't check out.
        if (!verifier.verify(manifestText, signatureDerBase64, key.getPublicKeyPem())) {
            throw rejected(SignatureException.unavailable("SIGNATURE_SELF_CHECK_FAILED",
                    "The signature could not be verified immediately after signing - nothing was recorded."),
                    tenantId, actorUserId, documentId, taskId);
        }

        DocumentSignature row = DocumentSignature.builder()
                .signatureId(UUID.randomUUID())
                .tenantId(tenantId)
                .documentId(documentId)
                .versionId(versionId)
                .workflowInstanceId(workflowInstanceId)
                .taskId(taskId)
                .signerUserId(actorUserId)
                .signerName(signerUser.getDisplayName())
                .signerEmail(signerUser.getEmail())
                .meaning(request.meaning())
                .commentText(comment)
                .manifestText(manifestText)
                .signatureDerBase64(signatureDerBase64)
                .publicKeyPem(key.getPublicKeyPem())
                .signingKeyId(key.getKeyId())
                .signingKeyName(key.getKeyName())
                .signedAt(signedAt)
                .createdBy(actorUserId)
                .visualSignatureKind(request.visualSignatureKind())
                .visualSignatureText(visualText)
                .visualSignatureImage(visualImage)
                .build();

        DocumentSignature saved;
        try {
            saved = signatureRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // uq_document_signatures_one_valid_per_task (V122) - a concurrent duplicate attempt on the same task lost
            // the race (never fires for a standalone row, whose taskId is always null).
            throw rejected(SignatureException.conflict("SIGNATURE_ALREADY_RECORDED",
                    "This task has already been signed."), tenantId, actorUserId, documentId, taskId);
        }

        // Cosmetic only (decision: never part of manifestText/what's verified) - best-effort save of
        // "what they used this time" as their new default, so next time's dialog starts pre-filled with it.
        visualSignatureService.upsert(tenantId, actorUserId, request.visualSignatureKind(), visualText,
                visualImage == null ? null : java.util.Base64.getEncoder().encodeToString(visualImage), actorUserId);

        Map<String, Object> auditValues = new java.util.HashMap<>();
        auditValues.put("documentId", documentId);
        auditValues.put("versionId", versionId);
        if (taskId != null) {
            auditValues.put("taskId", taskId);
        }
        auditValues.put("meaning", saved.getMeaning().name());
        auditService.event(AuditActionCatalog.DOCUMENT_SIGNED)
                .objectType(AuditObjectTypes.DOCUMENT_SIGNATURE)
                .objectId(saved.getSignatureId())
                .resourceName(version.getOriginalFilename())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(auditValues)
                .log();
        return saved;
    }

    private SignatureException rejected(SignatureException e, UUID tenantId, UUID actorUserId, UUID documentId,
                                        UUID taskId) {
        eventPublisher.publishEvent(new SignatureRejectedEvent(tenantId, actorUserId, documentId, taskId,
                e.getCode(), e.getMessage(), OffsetDateTime.now()));
        return e;
    }

    // ----------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public List<DocumentSignatureResponse> list(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        UUID current = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        return signatureRepository.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId).stream()
                .map(s -> toResponse(s, current))
                .toList();
    }

    @Transactional(readOnly = true)
    public SignatureEvidenceResponse evidence(UUID signatureId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentSignature signature = signatureRepository.findByTenantIdAndSignatureId(tenantId, signatureId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentSignature", signatureId));
        // Confirms the caller may see the document, same as any other read of it.
        documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(signature.getDocumentId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", signature.getDocumentId()));
        return new SignatureEvidenceResponse(signature.getSignatureId(), signature.getDocumentId(),
                signature.getVersionId(), signature.getManifestText(), signature.getSignatureDerBase64(),
                signature.getPublicKeyPem(), signature.getAlgorithm(), manifestField(signature, "fileSha256"),
                signature.getStatus().name(), signature.getLastVerificationResult() == null ? null
                        : signature.getLastVerificationResult().name(), signature.getSignedAt());
    }

    /**
     * Invalidates this task's signature, if it ever had one - part of retracting its approval
     * ({@code WorkflowTaskServiceImpl#retract}). Never deletes or rewrites the evidence columns (the
     * append-only trigger wouldn't allow it anyway): only {@code status}/{@code statusChangedAt}/
     * {@code statusReason} change, same as every other status transition this row can ever have. A no-op
     * (returns null) if this task was never signed, or its signature is already invalidated.
     * <p>
     * Looks up the VALID row directly (never {@code findByTenantIdAndTaskId}, which can no longer assume
     * at most one row per task once a task has been retracted and re-signed more than once -
     * uq_document_signatures_one_valid_per_task (V122) only guarantees uniqueness among valid rows, so a
     * plain task_id lookup against a task with prior invalidated attempts throws NonUniqueResultException).
     *
     * @return the version id it was signed against, so the caller can revert the document/instance back
     *         to the task's placement baseline - null if there was nothing to invalidate
     */
    @Transactional
    public UUID invalidateForTaskRetraction(UUID taskId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        return signatureRepository.findByTenantIdAndTaskIdAndStatus(tenantId, taskId, SignatureStatus.valid)
                .map(signature -> {
                    signature.setStatus(SignatureStatus.invalidated);
                    signature.setStatusChangedAt(OffsetDateTime.now());
                    signature.setStatusReason(reason);
                    DocumentSignature saved = signatureRepository.save(signature);
                    auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_INVALIDATED)
                            .objectType(AuditObjectTypes.DOCUMENT_SIGNATURE)
                            .objectId(saved.getSignatureId())
                            .outcome(AuditOutcome.SUCCESS)
                            .newValue(Map.of("documentId", saved.getDocumentId(), "taskId", taskId, "reason", reason))
                            .log();
                    return saved.getVersionId();
                })
                .orElse(null);
    }

    /** Same shape {@link #list} returns, for a single freshly-created signature - lets a caller (e.g. {@code StandaloneSignatureController#sign}) hand back the new row without a second round trip through {@link #list}. */
    @Transactional(readOnly = true)
    public DocumentSignatureResponse toPublicResponse(DocumentSignature signature) {
        Document document = documentRepository
                .findByDocumentIdAndTenantIdAndDeletedFalse(signature.getDocumentId(), TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", signature.getDocumentId()));
        UUID current = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        return toResponse(signature, current);
    }

    // ------------------------------------------------------------- verify

    @Transactional
    public VerifyAllResponse verifyAll(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        UUID current = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();

        List<DocumentSignature> signatures = signatureRepository
                .findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId);
        List<DocumentSignatureResponse> responses = new ArrayList<>();
        int valid = 0;
        int invalidated = 0;
        int unreadable = 0;
        for (DocumentSignature signature : signatures) {
            DocumentSignature checked = verifyOne(signature);
            VerificationResult result = checked.getLastVerificationResult();
            if (result == null) {
                unreadable++;
            } else if (result == VerificationResult.valid) {
                valid++;
            } else {
                invalidated++;
            }
            responses.add(toResponse(checked, current));
        }
        return new VerifyAllResponse(signatures.size(), valid, invalidated, unreadable, responses);
    }

    /**
     * {@code unreadable} changes nothing on the row (decision 5) - it is
     * inconclusive (storage down, version row missing), never evidence of
     * tampering. A {@code valid} result only ever stamps
     * last_verified_at/last_verification_result; it never revives an
     * already-{@code invalidated} signature's status - a workflow-outcome
     * invalidation is a separate, permanent fact about that signature.
     */
    private DocumentSignature verifyOne(DocumentSignature signature) {
        VerificationResult result = computeVerification(signature);
        if (result == VerificationResult.unreadable) {
            return signature;
        }
        OffsetDateTime now = OffsetDateTime.now();
        signature.setLastVerifiedAt(now);
        signature.setLastVerificationResult(result);
        if (result == VerificationResult.valid) {
            DocumentSignature saved = signatureRepository.save(signature);
            auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_VERIFIED)
                    .objectType(AuditObjectTypes.DOCUMENT_SIGNATURE)
                    .objectId(saved.getSignatureId())
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(Map.of("documentId", saved.getDocumentId(), "verificationResult", result.name()))
                    .log();
            return saved;
        }

        if (signature.isValid()) {
            signature.setStatus(SignatureStatus.invalidated);
            signature.setStatusChangedAt(now);
            signature.setStatusReason(result == VerificationResult.document_altered
                    ? "The document's stored file no longer matches what was signed."
                    : "The signature does not match its manifest or public key.");
        }
        DocumentSignature saved = signatureRepository.save(signature);
        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_VERIFICATION_FAILED)
                .objectType(AuditObjectTypes.DOCUMENT_SIGNATURE)
                .objectId(saved.getSignatureId())
                .outcome(AuditOutcome.FAILURE)
                .failureReason(result.name())
                .newValue(Map.of("documentId", saved.getDocumentId(), "verificationResult", result.name()))
                .log();
        eventPublisher.publishEvent(new SignatureVerificationFailedEvent(saved.getTenantId(),
                saved.getSignatureId(), saved.getDocumentId(), result.name()));
        return saved;
    }

    private VerificationResult computeVerification(DocumentSignature signature) {
        if (!verifier.verify(signature.getManifestText(), signature.getSignatureDerBase64(), signature.getPublicKeyPem())) {
            return VerificationResult.signature_mismatch;
        }
        String manifestHash = manifestField(signature, "fileSha256");
        if (manifestHash == null) {
            return VerificationResult.unreadable;
        }
        try {
            DocumentVersion version = versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(
                    signature.getVersionId(), signature.getDocumentId(), signature.getTenantId()).orElse(null);
            if (version == null) {
                return VerificationResult.unreadable;
            }
            byte[] bytes;
            try (InputStream in = contentReader.open(signature.getDocumentId(), signature.getVersionId()).content()) {
                bytes = in.readAllBytes();
            }
            return manifestHash.equalsIgnoreCase(sha256Hex(bytes))
                    ? VerificationResult.valid : VerificationResult.document_altered;
        } catch (RuntimeException | IOException e) {
            log.warn("Could not read document {} version {} to verify its signature - treating as unreadable",
                    signature.getDocumentId(), signature.getVersionId(), e);
            return VerificationResult.unreadable;
        }
    }

    private String manifestField(DocumentSignature signature, String field) {
        try {
            JsonNode root = mapper.readTree(signature.getManifestText());
            JsonNode value = root.path(field);
            return value.isMissingNode() || value.isNull() ? null : value.asText();
        } catch (IOException e) {
            return null;
        }
    }

    private DocumentSignatureResponse toResponse(DocumentSignature s, UUID currentVersionId) {
        int versionNumber = 0;
        try {
            JsonNode root = mapper.readTree(s.getManifestText());
            versionNumber = root.path("versionNumber").asInt(0);
        } catch (IOException ignored) {
            // The manifest is this signature's own evidence, not a live read - a parse failure
            // here is itself tamper evidence, not a reason to fail the whole list/verify call.
        }
        return new DocumentSignatureResponse(s.getSignatureId(), s.getDocumentId(), s.getVersionId(), versionNumber,
                s.getVersionId().equals(currentVersionId), s.getWorkflowInstanceId(), s.getTaskId(),
                s.getSignerUserId(), s.getSignerName(), s.getSignerEmail(), s.getMeaning().name(), s.getCommentText(),
                s.getStatus().name(), s.getStatusReason(), s.getStatusChangedAt(),
                s.getLastVerificationResult() == null ? null : s.getLastVerificationResult().name(),
                s.getLastVerifiedAt(), s.getSignedAt(),
                s.getVisualSignatureKind() == null ? null : s.getVisualSignatureKind().name(),
                s.getVisualSignatureText(),
                s.getVisualSignatureImage() == null ? null
                        : java.util.Base64.getEncoder().encodeToString(s.getVisualSignatureImage()));
    }

    private static String clean(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JVM has no SHA-256 implementation", e);
        }
    }
}
