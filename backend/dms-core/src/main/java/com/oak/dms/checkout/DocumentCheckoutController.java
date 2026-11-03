package com.oak.dms.checkout;

import com.oak.dms.scanning.controller.IngestionTicketResponses;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** 2.10 Check-in / Check-out (exclusive lock) and the legal hold that blocks it. */
@RestController
@RequestMapping("/api/v1/documents/{documentId}")
@RequiredArgsConstructor
@Tag(name = "Check-out / check-in", description = "Exclusive document locks and legal hold")
public class DocumentCheckoutController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final DocumentCheckoutService checkoutService;
    private final LegalHoldService legalHoldService;

    @GetMapping("/checkout")
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Whether the document is available, checked out (and by whom, since when), "
            + "on legal hold or signature-locked")
    public CheckoutStatusResponse status(@PathVariable UUID documentId) {
        return checkoutService.status(documentId);
    }

    @PostMapping("/checkout")
    @PreAuthorize("@authz.hasPermission(" + P + "CICO_CHECK_OUT)")
    @Operation(summary = "Check the document out - an exclusive lock only you can release by checking in",
            description = "409 DOCUMENT_CHECKED_OUT names who holds it and since when; 409 DOCUMENT_LEGAL_HOLD / "
                    + "DOCUMENT_SIGNATURE_LOCKED when it is frozen. Checking out what you already hold is a no-op.")
    public CheckoutStatusResponse checkOut(@PathVariable UUID documentId) {
        return checkoutService.checkOut(documentId);
    }

    /** Same ticket and status codes as a normal version upload - the version exists once the file is clean. */
    @PostMapping(value = "/checkin", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(" + P + "CICO_CHECK_IN)")
    @Operation(summary = "Check in - upload the edited file as a new version and release the lock",
            description = "The lock stays if the upload is refused outright (infected, failed) so you can retry.")
    public ResponseEntity<IngestionTicketResponse> checkIn(@PathVariable UUID documentId,
                                                           @RequestPart("file") MultipartFile file,
                                                           @RequestParam(value = "comment", required = false)
                                                           String comment) throws IOException {
        try (InputStream content = file.getInputStream()) {
            return IngestionTicketResponses.toResponseEntity(checkoutService.checkIn(documentId,
                    file.getOriginalFilename(), file.getSize(), content, comment));
        }
    }

    @PostMapping("/checkout/discard")
    @PreAuthorize("@authz.hasPermission(" + P + "CICO_DISCARD)")
    @Operation(summary = "Give up your check-out without creating a new version")
    public CheckoutStatusResponse discard(@PathVariable UUID documentId) {
        return checkoutService.discard(documentId);
    }

    @PostMapping("/checkout/force-release")
    @PreAuthorize("@authz.hasPermission(" + P + "CICO_FORCE_RELEASE)")
    @Operation(summary = "Force-release someone else's check-out (reason required, HIGH audit)")
    public CheckoutStatusResponse forceRelease(@PathVariable UUID documentId,
                                               @Valid @RequestBody ReasonRequest request) {
        return checkoutService.forceRelease(documentId, request.reason());
    }

    @PostMapping("/legal-hold")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_PLACE_LEGAL_HOLD)")
    @Operation(summary = "Place a legal hold - blocks check-out and every edit (reason required)")
    public void placeLegalHold(@PathVariable UUID documentId, @Valid @RequestBody ReasonRequest request) {
        legalHoldService.place(documentId, request.reason());
    }

    @PostMapping("/legal-hold/release")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_LIFT_LEGAL_HOLD)")
    @Operation(summary = "Lift a legal hold (reason required)")
    public void liftLegalHold(@PathVariable UUID documentId, @Valid @RequestBody ReasonRequest request) {
        legalHoldService.lift(documentId, request.reason());
    }
}
