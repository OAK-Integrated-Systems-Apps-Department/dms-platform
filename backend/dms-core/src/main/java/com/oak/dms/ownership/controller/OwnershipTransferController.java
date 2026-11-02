package com.oak.dms.ownership.controller;

import com.oak.dms.ownership.dto.DocumentOwnershipInfo;
import com.oak.dms.ownership.dto.TransferDocumentRequest;
import com.oak.dms.ownership.dto.TransferDocumentResponse;
import com.oak.dms.ownership.dto.TransferPreviewResponse;
import com.oak.dms.ownership.helper.OwnershipRetry;
import com.oak.dms.ownership.service.OwnershipInfoService;
import com.oak.dms.ownership.service.OwnershipTransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Single document ownership transfer (DOT-FR-002). Needs the DOT initiate-transfer permission; a tenant admin may
 * move any document, anyone else only documents owned by people in a department they head and can open.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Document ownership")
public class OwnershipTransferController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String INITIATE = "@authz.hasPermission(" + P + "DOT_TRANSFER_INITIATE)";

    private static final String VIEW = "@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)";

    private final OwnershipTransferService service;
    private final OwnershipInfoService infoService;
    private final OwnershipRetry retry;

    @GetMapping("/documents/{documentId}/ownership")
    @PreAuthorize(VIEW)
    @Operation(summary = "Who owns this document", description = "Owner, department, holding state and whether the caller may transfer it. 404 if the caller can't open it.")
    public DocumentOwnershipInfo info(@PathVariable("documentId") UUID documentId) {
        return infoService.info(documentId);
    }

    @PostMapping("/documents/{documentId}/ownership/transfer")
    @PreAuthorize(INITIATE)
    @Operation(summary = "Transfer one document to a new owner",
            description = "404 for a document the caller can't open (or a purged one); 403 OWNERSHIP_SCOPE_DENIED outside "
                    + "the caller's departments; 409 OWNERSHIP_UNCHANGED; 422 OWNERSHIP_TARGET_INVALID. Recycle Bin documents can be transferred.")
    public TransferDocumentResponse transfer(@PathVariable("documentId") UUID documentId,
                                             @Valid @RequestBody TransferDocumentRequest request) {
        return retry.run(() -> service.transferOne(documentId, request));
    }

    @PostMapping("/documents/{documentId}/ownership/transfer/preview")
    @PreAuthorize(INITIATE)
    @Operation(summary = "What a transfer would do, without doing it",
            description = "Tells whether the new owner can already open the document and whether it would be switched to Restricted.")
    public TransferPreviewResponse preview(@PathVariable("documentId") UUID documentId,
                                           @Valid @RequestBody TransferDocumentRequest request) {
        return service.preview(documentId, request);
    }
}
