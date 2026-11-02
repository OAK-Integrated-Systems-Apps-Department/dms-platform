package com.oak.dms.signature.controller;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.DocumentSignatureResponse;
import com.oak.dms.signature.dto.ReleaseSignatureLockRequest;
import com.oak.dms.signature.dto.SignatureEvidenceResponse;
import com.oak.dms.signature.dto.VerifyAllResponse;
import com.oak.dms.signature.service.DocumentSignatureLockService;
import com.oak.dms.signature.service.DocumentSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 1.4 Digital Signature Integration: a document's signatures, their
 * verification, independently-checkable evidence, and the signature lock
 * that an approved, validly-signed workflow places on its document.
 * Conditional on {@code dms.signature.enabled} like watermarking's own
 * controllers - while the feature is off these paths 404 as if they don't
 * exist, not exist and refuse (the one exception is the shared
 * {@code WorkflowTaskController#signAndApprove} endpoint - see
 * {@code NullDocumentSigner}'s own doc comment for why that one can't be
 * conditional the same way).
 */
@RestController
@ConditionalOnProperty(prefix = "dms.signature", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Document signatures", description = "Signature history, verification, evidence export, and the signature lock")
public class DocumentSignatureController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_VIEW_HISTORY = "@authz.hasPermission(" + P + "DSI_VIEW_SIGNATURE_HISTORY)";
    private static final String CAN_UNLOCK = "@authz.hasPermission(" + P + "DSI_UNLOCK_SIGNED_DOCUMENTS)";

    private final DocumentSignatureService signatureService;
    private final DocumentSignatureLockService lockService;

    @GetMapping("/api/v1/documents/{documentId}/signatures")
    @PreAuthorize(CAN_VIEW_HISTORY)
    @Operation(summary = "A document's signatures, newest first",
            description = "Each row names the version it was signed against and whether that is still the "
                    + "document's current version.")
    public List<DocumentSignatureResponse> list(@PathVariable UUID documentId) {
        return signatureService.list(documentId);
    }

    @PostMapping("/api/v1/documents/{documentId}/signatures/verify")
    @PreAuthorize(CAN_VIEW_HISTORY)
    @Operation(summary = "Re-verify every one of a document's signatures now (DSI-FR-003)",
            description = "Checks only the public key stored on each signature row - no round trip to the signing "
                    + "key service. A mismatch or an altered document marks that signature invalidated and alerts "
                    + "tenant admins; an unreadable file (storage temporarily unavailable) changes nothing.")
    public VerifyAllResponse verifyAll(@PathVariable UUID documentId) {
        return signatureService.verifyAll(documentId);
    }

    @GetMapping("/api/v1/signatures/{signatureId}/evidence")
    @PreAuthorize(CAN_VIEW_HISTORY)
    @Operation(summary = "Everything needed to verify a signature independently of this application",
            description = "The exact signed manifest text, the DER signature, the public key PEM, and the "
                    + "version's recorded file hash - e.g. `openssl dgst -sha256 -verify pub.pem -signature sig.der manifest.json`.")
    public SignatureEvidenceResponse evidence(@PathVariable UUID signatureId) {
        return signatureService.evidence(signatureId);
    }

    @PostMapping("/api/v1/documents/{documentId}/signature-lock/release")
    @PreAuthorize(CAN_UNLOCK)
    @Operation(summary = "Unlock a signature-locked document (reason required)",
            description = "Does NOT invalidate the signature(s) that locked it - they stay valid for the version "
                    + "they signed. 409 DOCUMENT_NOT_SIGNATURE_LOCKED if it isn't currently locked.")
    public void release(@PathVariable UUID documentId, @Valid @RequestBody ReleaseSignatureLockRequest request) {
        lockService.unlock(documentId, request.reason(), TenantContext.getUserId());
    }
}
