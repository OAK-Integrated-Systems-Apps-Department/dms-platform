package com.oak.dms.sharing;

import com.oak.dms.sharing.SharingDtos.ConfidentialResult;
import com.oak.dms.sharing.SharingDtos.CreateGrant;
import com.oak.dms.sharing.SharingDtos.CreateLink;
import com.oak.dms.sharing.SharingDtos.Eligibility;
import com.oak.dms.sharing.SharingDtos.Guest;
import com.oak.dms.sharing.SharingDtos.GuestGrantView;
import com.oak.dms.sharing.SharingDtos.InviteGuest;
import com.oak.dms.sharing.SharingDtos.Link;
import com.oak.dms.sharing.SharingDtos.LinkCreated;
import com.oak.dms.sharing.SharingDtos.Page;
import com.oak.dms.sharing.SharingDtos.PatchGrant;
import com.oak.dms.sharing.SharingDtos.PatchGuest;
import com.oak.dms.sharing.SharingDtos.SetConfidential;
import com.oak.dms.sharing.SharingDtos.Settings;
import com.oak.dms.sharing.SharingDtos.ShareEventView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 1.5 Sharing and External Access, for signed-in users - see docs/external-sharing-design.md. The permission codes
 * gate WHAT a person may do; what they may do it TO (edit access on the document or folder) is checked in the
 * services, which also apply the organisation's policy and the confidential / retention rules.
 */
@RestController
@RequestMapping("/api/v1/sharing")
@RequiredArgsConstructor
@Tag(name = "Sharing", description = "External share links, guest access and the sharing policy")
public class SharingController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_SHARE = "@authz.hasAnyPermission(" + P + "SEA_CREATE_LINK, " + P + "SEA_INVITE_GUEST, "
            + P + "SEA_UPDATE_GUEST)";

    private final SharingSettingsService settingsService;
    private final ShareLinkService linkService;
    private final GuestService guestService;
    private final ConfidentialService confidentialService;

    // ---- policy ----

    @GetMapping("/settings")
    @Operation(summary = "The organisation's sharing policy - anyone signed in may read it, the share sheet needs it")
    public Settings settings() {
        return settingsService.get();
    }

    @PutMapping("/settings")
    @PreAuthorize("@authz.isTenantAdmin()")
    @Operation(summary = "Change the organisation's sharing policy (tenant administrators)")
    public Settings updateSettings(@RequestBody Settings request) {
        return settingsService.update(request);
    }

    @GetMapping("/eligibility")
    @PreAuthorize(CAN_SHARE)
    @Operation(summary = "Can this document or folder be shared externally - and if not, why")
    public Eligibility eligibility(@RequestParam("targetType") String targetType, @RequestParam("targetId") UUID targetId) {
        return linkService.eligibility(targetType, targetId);
    }

    @PutMapping("/confidential")
    @Operation(summary = "Mark a document or folder confidential (or clear it - administrators)",
            description = "Marking applies the organisation's policy to links already out there: flagged for review "
                    + "or revoked; guest access to it is revoked.")
    public ConfidentialResult confidential(@RequestBody SetConfidential request) {
        return confidentialService.set(request);
    }

    // ---- links ----

    @GetMapping("/links")
    @PreAuthorize("@authz.hasAnyPermission(" + P + "SEA_CREATE_LINK, " + P + "SEA_REVOKE_SINGLE)")
    @Operation(summary = "The share links on one document or folder")
    public List<Link> links(@RequestParam("targetType") String targetType, @RequestParam("targetId") UUID targetId) {
        return linkService.listFor(targetType, targetId);
    }

    @GetMapping("/links/all")
    @PreAuthorize("@authz.isTenantAdmin()")
    @Operation(summary = "Every share link in the organisation (tenant administrators)")
    public Page<Link> allLinks(@RequestParam(name = "status", defaultValue = "all") String status,
                               @RequestParam(name = "page", defaultValue = "0") int page,
                               @RequestParam(name = "size", defaultValue = "25") int size) {
        return linkService.all(status, page, size);
    }

    @PostMapping("/links")
    @PreAuthorize("@authz.hasPermission(" + P + "SEA_CREATE_LINK)")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an external share link",
            description = "The link's URL is in this response and nowhere else - only a hash of the token is kept.")
    public LinkCreated createLink(@RequestBody CreateLink request) {
        return linkService.create(request);
    }

    @DeleteMapping("/links/{linkId}")
    @PreAuthorize("@authz.hasPermission(" + P + "SEA_REVOKE_SINGLE)")
    @Operation(summary = "Revoke a share link - it stops working at once")
    public ResponseEntity<Void> revokeLink(@PathVariable("linkId") UUID linkId) {
        linkService.revoke(linkId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/links/{linkId}/clear-review")
    @PreAuthorize("@authz.isTenantAdmin()")
    @Operation(summary = "Keep a link that was flagged for review after its item became confidential")
    public ResponseEntity<Void> clearReview(@PathVariable("linkId") UUID linkId) {
        linkService.clearReview(linkId);
        return ResponseEntity.noContent().build();
    }

    // ---- guests ----

    @GetMapping("/guests")
    @PreAuthorize("@authz.hasPermission(" + P + "SEA_VIEW_GUESTS)")
    @Operation(summary = "The organisation's guest accounts")
    public List<Guest> guests() {
        return guestService.guests();
    }

    @PostMapping("/guests/invite")
    @PreAuthorize("@authz.hasPermission(" + P + "SEA_INVITE_GUEST)")
    @Operation(summary = "Invite a guest to the client portal (re-sends a pending invitation)")
    public Guest invite(@RequestBody InviteGuest request) {
        return guestService.invite(request.email(), request.displayName());
    }

    @PatchMapping("/guests/{guestId}")
    @PreAuthorize("@authz.hasAnyPermission(" + P + "SEA_UPDATE_GUEST, " + P + "SEA_REVOKE_GUEST)")
    @Operation(summary = "Switch a guest account off or back on")
    public Guest patchGuest(@PathVariable("guestId") UUID guestId, @RequestBody PatchGuest request) {
        return guestService.setStatus(guestId, request.status());
    }

    @DeleteMapping("/guests/{guestId}")
    @PreAuthorize("@authz.hasPermission(" + P + "SEA_REVOKE_GUEST)")
    @Operation(summary = "Delete a guest account and all its access")
    public ResponseEntity<Void> deleteGuest(@PathVariable("guestId") UUID guestId) {
        guestService.delete(guestId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/guest-grants")
    @PreAuthorize(CAN_SHARE + " or @authz.hasPermission(" + P + "SEA_VIEW_GUESTS)")
    @Operation(summary = "The guests who have access to one document or folder")
    public List<GuestGrantView> grants(@RequestParam("targetType") String targetType,
                                       @RequestParam("targetId") UUID targetId) {
        return guestService.grants(targetType, targetId);
    }

    @PostMapping("/guest-grants")
    @PreAuthorize("@authz.hasAnyPermission(" + P + "SEA_INVITE_GUEST, " + P + "SEA_UPDATE_GUEST)")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Give a guest access to a document or folder (invites them if new)")
    public GuestGrantView createGrant(@RequestBody CreateGrant request) {
        return guestService.createGrant(request);
    }

    @PatchMapping("/guest-grants/{grantId}")
    @PreAuthorize("@authz.hasPermission(" + P + "SEA_UPDATE_GUEST)")
    @Operation(summary = "Change a guest's access level or end date")
    public GuestGrantView patchGrant(@PathVariable("grantId") UUID grantId, @RequestBody PatchGrant request) {
        return guestService.updateGrant(grantId, request);
    }

    @DeleteMapping("/guest-grants/{grantId}")
    @PreAuthorize("@authz.hasPermission(" + P + "SEA_REVOKE_GUEST)")
    @Operation(summary = "Take a guest's access to one item away")
    public ResponseEntity<Void> revokeGrant(@PathVariable("grantId") UUID grantId) {
        guestService.revokeGrant(grantId);
        return ResponseEntity.noContent().build();
    }

    // ---- activity ----

    @GetMapping("/events")
    @Operation(summary = "The sharing log",
            description = "For a link or an item: anyone with edit access to it. Organisation-wide: sea.view-sharing-activity-log.view.")
    public Page<ShareEventView> events(@RequestParam(name = "targetType", required = false) String targetType,
                                       @RequestParam(name = "targetId", required = false) UUID targetId,
                                       @RequestParam(name = "linkId", required = false) UUID linkId,
                                       @RequestParam(name = "page", defaultValue = "0") int page,
                                       @RequestParam(name = "size", defaultValue = "25") int size) {
        return linkService.events(targetType, targetId, linkId, page, size);
    }
}
