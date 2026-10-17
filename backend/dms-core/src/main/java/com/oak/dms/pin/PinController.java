package com.oak.dms.pin;

import com.oak.dms.pin.PinService.Pins;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** The caller's pinned documents - see {@link PinService}. Permission codes from V10's PID module. */
@RestController
@RequestMapping("/api/v1/pins")
@RequiredArgsConstructor
@Tag(name = "Pinned documents", description = "A user's own shortcuts to the documents they use most")
public class PinController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final PinService service;

    public record LimitRequest(@Min(PinService.MIN_LIMIT) @Max(PinService.MAX_LIMIT) int maxPins) {
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "The caller's pinned documents, newest pin first, and how many they may have",
            description = "Read through the document browser's access rules: a pin whose document the caller "
                    + "can no longer see, or that was deleted, is removed. An archived document stays, flagged.")
    public Pins list() {
        return service.list();
    }

    @PutMapping("/{documentId}")
    @PreAuthorize("@authz.hasPermission(" + P + "PID_PIN)")
    @Operation(summary = "Pin a document the caller can see",
            description = "Pinning one already pinned is not an error. 409 PIN_LIMIT_REACHED at the limit.")
    public ResponseEntity<Void> pin(@PathVariable("documentId") UUID documentId) {
        service.pin(documentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{documentId}")
    @PreAuthorize("@authz.hasPermission(" + P + "PID_UNPIN)")
    @Operation(summary = "Unpin a document", description = "Quietly does nothing when it wasn't pinned.")
    public ResponseEntity<Void> unpin(@PathVariable("documentId") UUID documentId) {
        service.unpin(documentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/limit")
    @PreAuthorize("@authz.hasPermission(" + P + "PID_CONFIGURE_LIMITS)")
    @Operation(summary = "Set how many documents one person in this organisation may pin",
            description = "Existing pins are kept when it is lowered; people just can't add more until under it.")
    public Map<String, Integer> setLimit(@Valid @RequestBody LimitRequest request) {
        return Map.of("maxPins", service.setLimit(request.maxPins()));
    }
}
