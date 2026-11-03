package com.oak.dms.compliance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** One document's lifecycle: status, retention, and what an administrator decides after its total retention. */
@RestController
@RequestMapping("/api/v1/documents/{documentId}")
@RequiredArgsConstructor
@Tag(name = "Records retention", description = "Active / Inactive status, retention and legal-hold-aware lifecycle")
public class DocumentLifecycleController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    public record StatusRequest(String status, String reason) {
    }

    public record ExtendRequest(int amount, String unit, String reason) {
    }

    public record ReasonRequest(String reason) {
    }

    private final DocumentLifecycleService service;

    @GetMapping("/retention")
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "A document's status and where it stands in its retention")
    public DocumentLifecycleService.RetentionView retention(@PathVariable UUID documentId) {
        return service.retention(documentId);
    }

    @PutMapping("/status")
    @PreAuthorize("@authz.hasAnyPermission(" + P + "CRM_MARK_ACTIVE, " + P + "CRM_MARK_INACTIVE)")
    @Operation(summary = "Switch a document between Active and Inactive",
            description = "status is active or inactive; each needs its own permission. 409 DOCUMENT_ON_LEGAL_HOLD (naming the "
                    + "hold and its reason) while the document is under legal hold. A reason is optional unless the "
                    + "organisation requires one (400 STATUS_REASON_REQUIRED). Audited.")
    public DocumentLifecycleService.RetentionView changeStatus(@PathVariable UUID documentId, @RequestBody StatusRequest request) {
        return service.changeStatus(documentId, request.status(), request.reason());
    }

    @PostMapping("/retention/extend")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_REVIEW_RETENTION)")
    @Operation(summary = "Keep a document past its total retention instead of disposing of it",
            description = "Only for a document flagged for review. Needs a reason. Audited.")
    public DocumentLifecycleService.RetentionView extend(@PathVariable UUID documentId, @RequestBody ExtendRequest request) {
        return service.extend(documentId, request.amount(), request.unit(), request.reason());
    }

    @PostMapping("/retention/dispose")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_REVIEW_RETENTION)")
    @Operation(summary = "Send a document past its total retention to the recycle bin",
            description = "Only for a document flagged for review; refused while it is under legal hold, in an active approval "
                    + "or linked to other documents (409 DOCUMENT_REFERENCED). Needs a reason. Audited.")
    public void dispose(@PathVariable UUID documentId, @RequestBody ReasonRequest request) {
        service.dispose(documentId, request.reason());
    }
}
