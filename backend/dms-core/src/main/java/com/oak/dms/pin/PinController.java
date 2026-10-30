package com.oak.dms.pin;

import com.oak.dms.pin.PinService.Audience;
import com.oak.dms.pin.PinService.AudienceRequest;
import com.oak.dms.pin.PinService.Member;
import com.oak.dms.pin.PinService.Pins;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pinned documents and who sees them - see {@link PinService}. Permission codes from V10's PID module. */
@RestController
@RequestMapping("/api/v1/pins")
@RequiredArgsConstructor
@Tag(name = "Pinned documents", description = "Shortcuts to important documents, shown to the audience the pinner chose")
public class PinController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final PinService service;

    public record LimitRequest(@Min(PinService.MIN_LIMIT) @Max(PinService.MAX_LIMIT) int maxPins) {
    }

    /** Required: a pin with no audience is refused, so an out-of-date client can't quietly show it to everyone. */
    public record PinRequest(@NotNull PinService.AudienceMode mode, List<UUID> userIds) {
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "The pins the caller can see, newest first, and how many documents they may pin",
            description = "Pins the caller placed, plus pins whose audience includes them - all read through the "
                    + "document browser's access rules. A pin the caller placed on a document they can no longer "
                    + "see, or that was deleted, is removed; someone else's pin is only hidden. An archived "
                    + "document stays, flagged.")
    public Pins list() {
        return service.list();
    }

    @PutMapping("/{documentId}")
    @PreAuthorize("@authz.hasPermission(" + P + "PID_PIN)")
    @Operation(summary = "Pin a document the caller can see, for the audience they choose",
            description = "Mode ALL shows it to everyone except the people in userIds; mode USERS shows it only to "
                    + "the people in userIds. Pinning a document the caller already pinned changes its audience. "
                    + "The pinner always sees their own pin. 409 PIN_LIMIT_REACHED when a new pin is over the limit.")
    public ResponseEntity<Void> pin(@PathVariable("documentId") UUID documentId,
                                    @Valid @RequestBody PinRequest request) {
        service.pin(documentId, new AudienceRequest(request.mode(), request.userIds()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{documentId}")
    @PreAuthorize("@authz.hasPermission(" + P + "PID_UNPIN)")
    @Operation(summary = "Unpin a document the caller pinned",
            description = "Removes the caller's own pin, for everyone it was shown to. Only the person who pinned it "
                    + "can do this: anyone else's request finds nothing of theirs to remove, and does nothing.")
    public ResponseEntity<Void> unpin(@PathVariable("documentId") UUID documentId) {
        service.unpin(documentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{documentId}/audience")
    @PreAuthorize("@authz.hasPermission(" + P + "PID_PIN)")
    @Operation(summary = "Who the caller's own pin on a document is shown to",
            description = "404 when the caller has no pin on it.")
    public Audience audience(@PathVariable("documentId") UUID documentId) {
        return service.audience(documentId);
    }

    @GetMapping("/audience-candidates")
    @PreAuthorize("@authz.hasPermission(" + P + "PID_PIN)")
    @Operation(summary = "People the caller can choose when picking an audience",
            description = "Active people in the caller's organisation whose name or email contains the search text. "
                    + "Needs only the pin permission, not the user administration ones.")
    public List<Member> candidates(@RequestParam(name = "q", defaultValue = "") String query,
                                   @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return service.candidates(query, limit);
    }

    @PutMapping("/limit")
    @PreAuthorize("@authz.hasPermission(" + P + "PID_CONFIGURE_LIMITS)")
    @Operation(summary = "Set how many documents one person in this organisation may pin",
            description = "Existing pins are kept when it is lowered; people just can't add more until under it.")
    public Map<String, Integer> setLimit(@Valid @RequestBody LimitRequest request) {
        return Map.of("maxPins", service.setLimit(request.maxPins()));
    }
}
