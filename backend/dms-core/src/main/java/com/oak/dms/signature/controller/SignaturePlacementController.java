package com.oak.dms.signature.controller;

import com.oak.dms.signature.dto.PlaceSignatureRequest;
import com.oak.dms.signature.dto.PlaceSignatureResponse;
import com.oak.dms.signature.dto.SignaturePlacementInfoResponse;
import com.oak.dms.signature.service.SignaturePlacementService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 1.4 Digital Signature Integration: placing a signer's visual signature on
 * the actual document page, as a real mark burned into a new document
 * version - a separate step strictly BEFORE sign-and-approve (see
 * {@link SignaturePlacementService}'s own doc comment for why it can never
 * be folded into that transaction). Same permission gate as
 * {@code /sign-and-approve} - placing a mark on the page is part of the same
 * signing ceremony, just its own request.
 */
@RestController
@RequestMapping("/api/v1/workflow/tasks/{taskId}/signature-placement")
@ConditionalOnProperty(prefix = "dms.signature", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Signature placement", description = "Stamp a visual signature onto a document's page before signing")
public class SignaturePlacementController {

    private static final String CAN_SIGN = "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_APPROVE) "
            + "and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DSI_SIGN)";

    private final SignaturePlacementService service;

    @GetMapping
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Whether this task's document can be stamped, and how many pages/images it has")
    public SignaturePlacementInfoResponse info(@PathVariable UUID taskId) {
        return service.info(taskId);
    }

    @GetMapping(value = "/pages/{page}", produces = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Page/image {page} (0-based) as a preview for the placement UI to drag the signature onto")
    public ResponseEntity<byte[]> page(@PathVariable UUID taskId, @PathVariable int page,
                                       @RequestParam(defaultValue = "1000") int width) {
        byte[] image = service.pagePreview(taskId, page, width);
        return ResponseEntity.ok().body(image);
    }

    @PostMapping
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Stamp the signer's visual signature onto the given page/rectangle",
            description = "Creates a new document version (reason=signed_copy, scanned and encrypted like any "
                    + "other) and advances this task's workflow instance to sign that version next. Call this "
                    + "once, then POST /sign-and-approve on the same task.")
    public PlaceSignatureResponse place(@PathVariable UUID taskId, @Valid @RequestBody PlaceSignatureRequest request) {
        return service.place(taskId, request);
    }

    @DeleteMapping
    @PreAuthorize(CAN_SIGN)
    @Operation(summary = "Remove this task's placed signature from the document",
            description = "Re-points the task back at the version it stamped from - the stamped version itself "
                    + "is kept (never deleted), just no longer what will be signed. 409 if this task hasn't "
                    + "placed a signature yet.")
    public void remove(@PathVariable UUID taskId) {
        service.remove(taskId);
    }
}
