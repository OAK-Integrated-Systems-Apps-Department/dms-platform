package com.oak.dms.signature.controller;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.DocumentSignatureResponse;
import com.oak.dms.signature.dto.PlaceSignatureRequest;
import com.oak.dms.signature.dto.PlaceSignatureResponse;
import com.oak.dms.signature.dto.SignAndApproveRequest;
import com.oak.dms.signature.dto.SignaturePlacementInfoResponse;
import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.enums.SignatureMeaning;
import com.oak.dms.signature.service.DocumentSignatureService;
import com.oak.dms.signature.service.StandaloneSignaturePlacementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 1.4 Digital Signature Integration (V119): add an e-signature straight to a
 * document - a real mark plus Name/Designation/Date burned into a new
 * version, then cryptographically signed, same ceremony as sign-and-approve
 * but with no workflow task/approval behind it. Gated by a permission
 * distinct from task-scoped signing ({@code DSI_SIGN}, always paired with
 * {@code WA_TASK_APPROVE}) so the two can be granted independently, combined
 * with the same create-a-new-version permission the regular "upload a new
 * version" endpoint uses, since this mutates the document the same way.
 */
@RestController
@ConditionalOnProperty(prefix = "dms.signature", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Standalone document signing", description = "Add or remove an e-signature on a document directly, outside of workflow approval")
public class StandaloneSignatureController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_SIGN = "@authz.hasPermission(" + P + "DSI_SIGN_DOCUMENT_STANDALONE) "
            + "and @authz.hasPermission(" + P + "DOCM_DOCUMENTS_CREATE)";

    private final StandaloneSignaturePlacementService placementService;
    private final DocumentSignatureService signatureService;

    @GetMapping("/api/v1/documents/{documentId}/signature-placement")
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Whether this document can be stamped, and how many pages it has")
    public SignaturePlacementInfoResponse info(@PathVariable UUID documentId) {
        return placementService.info(documentId);
    }

    @GetMapping(value = "/api/v1/documents/{documentId}/signature-placement/pages/{page}",
            produces = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Page/image {page} (0-based) as a preview for the placement UI to drag the signature onto")
    public ResponseEntity<byte[]> page(@PathVariable UUID documentId, @PathVariable int page,
                                       @RequestParam(defaultValue = "1000") int width) {
        return ResponseEntity.ok().body(placementService.pagePreview(documentId, page, width));
    }

    @PostMapping("/api/v1/documents/{documentId}/signature-placement/pages")
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Append a blank page to make room for the signature block",
            description = "Only when no existing page has room - there's no reliable way to detect that "
                    + "automatically, so the signer chooses this explicitly. The new page becomes the "
                    + "document's last page; place the signature on it next like any other page. PDF only.")
    public SignaturePlacementInfoResponse addPage(@PathVariable UUID documentId) {
        return placementService.addPage(documentId);
    }

    @PostMapping("/api/v1/documents/{documentId}/signature-placement")
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Stamp the signer's visual signature (with Name/Designation/Date) onto the given page/rectangle",
            description = "Creates a new document version (reason=signed_copy, scanned and encrypted like any "
                    + "other) and makes it the document's current version. Call this once, then POST .../sign.")
    public PlaceSignatureResponse place(@PathVariable UUID documentId, @Valid @RequestBody PlaceSignatureRequest request) {
        return placementService.place(documentId, request);
    }

    @DeleteMapping("/api/v1/documents/{documentId}/signature-placement")
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Remove the standalone signature block from this document",
            description = "Reverts the document back to the version from before this signing session started "
                    + "(any page added and any mark placed since) - that version itself is kept, never "
                    + "deleted, just no longer current. 409 if nothing is currently placed.")
    public void remove(@PathVariable UUID documentId) {
        placementService.remove(documentId);
    }

    @PostMapping("/api/v1/documents/{documentId}/sign")
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Cryptographically sign the document's current version",
            description = "Call after placing the mark. Always records meaning=acknowledged - there is no "
                    + "approval decision here, just the signer's own standalone e-signature.")
    public DocumentSignatureResponse sign(@PathVariable UUID documentId, @Valid @RequestBody SignAndApproveRequest request) {
        SignAndApproveRequest standalone = new SignAndApproveRequest(request.comment(), SignatureMeaning.acknowledged,
                request.consent(), request.password(), request.totp(), request.visualSignatureKind(),
                request.visualSignatureText(), request.visualSignatureImageBase64());
        DocumentSignature saved = signatureService.signStandalone(documentId, standalone);
        return signatureService.toPublicResponse(saved);
    }
}
