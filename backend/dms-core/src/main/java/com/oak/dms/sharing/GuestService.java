package com.oak.dms.sharing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.sharing.ShareTargets.Target;
import com.oak.dms.sharing.SharingDtos.CreateGrant;
import com.oak.dms.sharing.SharingDtos.Guest;
import com.oak.dms.sharing.SharingDtos.GuestGrantView;
import com.oak.dms.sharing.SharingDtos.PatchGrant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Guest accounts and their access (SEA-FR-004, -005, -006), managed by an internal user: invite someone from outside,
 * give them documents and folders through the client portal, change or take that access away, switch the account
 * off. The guest's own side (accepting the invitation, signing in, browsing) is {@link PortalService}.
 *
 * <p>A guest is tenant-scoped with credentials of its own - never a Keycloak user, never in a role - so the most one
 * can ever reach is what a {@link GuestGrant} names, at {@code view} or {@code download}.
 */
@Service
@RequiredArgsConstructor
public class GuestService {

    static final long INVITE_DAYS = 7;

    private final GuestAccountRepository guestRepository;
    private final GuestGrantRepository grantRepository;
    private final ShareTargets targets;
    private final ShareEventLog eventLog;
    private final SharingSettingsService settingsService;
    private final SharingTokens tokens;
    private final SharingMailer mailer;
    private final ShareLinkService linkService;
    private final AuthorizationService authz;

    // ------------------------------------------------------------------ guests

    @Transactional(readOnly = true)
    public List<Guest> guests() {
        UUID tenantId = TenantContext.getTenantId();
        return guestRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(this::view).toList();
    }

    /** Invites a person (or re-sends the invitation to one who hasn't accepted yet). */
    @Transactional
    public Guest invite(String email, String displayName) {
        UUID tenantId = TenantContext.getTenantId();
        requireEnabled(tenantId);
        String address = requireEmail(email);
        GuestAccount guest = guestRepository.findByTenantIdAndEmail(tenantId, address).orElse(null);
        if (guest != null && GuestAccount.DISABLED.equals(guest.getStatus())) {
            throw SharingException.conflict("GUEST_DISABLED", "This guest account is switched off. Enable it first.");
        }
        if (guest == null) {
            guest = createGuest(tenantId, address, displayName);
        }
        if (GuestAccount.INVITED.equals(guest.getStatus())) {
            sendInvite(guest);
        }
        return view(guest);
    }

    @Transactional
    public Guest setStatus(UUID guestId, String status) {
        UUID tenantId = TenantContext.getTenantId();
        GuestAccount guest = find(tenantId, guestId);
        String wanted = status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
        String me = linkService.displayName(TenantContext.getUserId());
        if (GuestAccount.DISABLED.equals(wanted)) {
            if (!GuestAccount.DISABLED.equals(guest.getStatus())) {
                guest.setStatus(GuestAccount.DISABLED);
                guest.setDisabledReason(GuestAccount.DISABLED_BY_ADMIN);
                guestRepository.save(guest);
                eventLog.record(guestEvent(tenantId, ShareEventLog.GUEST_DISABLED, guest, TenantContext.getUserId(), me,
                        "switched off by " + me));
                eventLog.audit(AuditActionCatalog.GUEST_DISABLED, tenantId, AuditObjectTypes.GUEST_ACCOUNT,
                        guest.getGuestId(), guest.getEmail(), true, me, Map.of("reason", "admin"));
            }
        } else if (GuestAccount.ACTIVE.equals(wanted)) {
            if (GuestAccount.DISABLED.equals(guest.getStatus())) {
                boolean hadPassword = guest.getPasswordHash() != null;
                guest.setStatus(hadPassword ? GuestAccount.ACTIVE : GuestAccount.INVITED);
                guest.setDisabledReason(null);
                guest.setFailedAttempts(0);
                guest.setLockedUntil(null);
                // Switching an account back on counts as activity: otherwise the inactivity sweep, seeing a sign-in
                // from months ago, would switch it straight off again.
                guest.setLastLoginAt(OffsetDateTime.now());
                guestRepository.save(guest);
                if (!hadPassword) {
                    sendInvite(guest);
                }
            } else if (GuestAccount.INVITED.equals(guest.getStatus())) {
                throw SharingException.badRequest("GUEST_NOT_ACCEPTED",
                        "This person hasn't accepted the invitation yet - they activate their own account.");
            }
        } else {
            throw SharingException.badRequest("GUEST_STATUS_INVALID", "Status must be active or disabled.");
        }
        return view(guest);
    }

    /** Removes the account and every grant it had. The sharing log keeps its history. */
    @Transactional
    public void delete(UUID guestId) {
        UUID tenantId = TenantContext.getTenantId();
        GuestAccount guest = find(tenantId, guestId);
        String me = linkService.displayName(TenantContext.getUserId());
        guestRepository.delete(guest);
        eventLog.record(guestEvent(tenantId, ShareEventLog.GUEST_GRANT_REVOKED, guest, TenantContext.getUserId(), me,
                "account deleted by " + me));
        eventLog.audit(AuditActionCatalog.GUEST_DELETED, tenantId, AuditObjectTypes.GUEST_ACCOUNT, guestId,
                guest.getEmail(), true, me, Map.of());
    }

    // ------------------------------------------------------------------ grants

    @Transactional(readOnly = true)
    public List<GuestGrantView> grants(String targetType, UUID targetId) {
        UUID tenantId = TenantContext.getTenantId();
        Target target = targets.resolve(tenantId, ShareTargets.normaliseType(targetType), targetId);
        linkService.requireEdit(target);
        List<GuestGrant> grants = target.isDocument()
                ? grantRepository.findByTenantIdAndDocumentIdAndRevokedAtIsNullOrderByCreatedAtDesc(tenantId, targetId)
                : grantRepository.findByTenantIdAndFolderIdAndRevokedAtIsNullOrderByCreatedAtDesc(tenantId, targetId);
        return grants.stream().map(g -> grantView(g, target.name())).toList();
    }

    /** Gives a guest (created and invited on the spot if the address is new) access to a document or folder. */
    @Transactional
    public GuestGrantView createGrant(CreateGrant request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        requireEnabled(tenantId);
        String type = ShareTargets.normaliseType(request.targetType());
        Target target = targets.resolve(tenantId, type, request.targetId());
        linkService.requireEdit(target);
        String access = ShareLinkService.normaliseAccess(request.access());
        if (ShareLink.ACCESS_DOWNLOAD.equals(access) && !authz.hasPermission(PermissionCodes.SEA_SET_PERMISSION_LEVEL)) {
            throw SharingException.forbidden("SHARING_NOT_ALLOWED",
                    "You can give guests view-only access; ask an administrator for permission to allow downloads.");
        }
        String overrideReason = linkService.checkEligibility(target, tenantId, request.overrideRestriction(),
                request.overrideReason());
        if (request.expiresAt() != null && !request.expiresAt().isAfter(OffsetDateTime.now().plusMinutes(1))) {
            throw SharingException.badRequest("SHARING_EXPIRY_INVALID", "The end date must be in the future.");
        }

        String address = requireEmail(request.email());
        GuestAccount guest = guestRepository.findByTenantIdAndEmail(tenantId, address).orElse(null);
        boolean newGuest = guest == null;
        if (!authz.hasPermission(newGuest ? PermissionCodes.SEA_INVITE_GUEST : PermissionCodes.SEA_UPDATE_GUEST)) {
            throw SharingException.forbidden("SHARING_NOT_ALLOWED", newGuest
                    ? "You don't have permission to invite guests."
                    : "You don't have permission to change guest access.");
        }
        if (guest != null && GuestAccount.DISABLED.equals(guest.getStatus())) {
            throw SharingException.conflict("GUEST_DISABLED", "This guest account is switched off. Enable it first.");
        }
        if (newGuest) {
            guest = createGuest(tenantId, address, request.displayName());
        }

        // One live grant per guest and item: sharing again changes it rather than stacking a second.
        GuestAccount owner = guest;
        GuestGrant grant = grantRepository.findByGuestIdAndRevokedAtIsNull(guest.getGuestId()).stream()
                .filter(g -> type.equals(g.getTargetType()) && target.id().equals(g.targetId()))
                .findFirst().orElse(null);
        boolean created = grant == null;
        if (created) {
            grant = GuestGrant.builder().tenantId(tenantId).guestId(owner.getGuestId()).targetType(type)
                    .documentId(target.isDocument() ? target.id() : null)
                    .folderId(target.isDocument() ? null : target.id()).createdBy(userId).build();
        }
        grant.setAccess(access);
        grant.setExpiresAt(request.expiresAt());
        if (overrideReason != null) {
            grant.setOverrideReason(overrideReason);
        }
        grant = grantRepository.save(grant);

        String me = linkService.displayName(userId);
        eventLog.record(new ShareEventLog.Subject(tenantId, ShareEventLog.GUEST_GRANT_CREATED, null, guest.getGuestId(),
                type, target.id(), target.name(), userId, me,
                guest.getEmail() + " - " + access + (overrideReason != null ? ", administrator override" : "")));
        eventLog.audit(AuditActionCatalog.GUEST_ACCESS_GRANTED, tenantId, AuditObjectTypes.GUEST_ACCOUNT, guest.getGuestId(),
                guest.getEmail(), true, me, Map.of("targetType", type, "targetId", target.id(), "access", access));

        if (GuestAccount.INVITED.equals(guest.getStatus())) {
            sendInvite(guest);
        } else if (created) {
            mailer.sendAccessGranted(guest.getEmail(), me, linkService.organisationName(tenantId), target.name(),
                    mailer.portalUrl(tenantId));
        }
        return grantView(grant, target.name());
    }

    @Transactional
    public GuestGrantView updateGrant(UUID grantId, PatchGrant request) {
        UUID tenantId = TenantContext.getTenantId();
        GuestGrant grant = findGrant(tenantId, grantId);
        Target target = targets.resolve(tenantId, grant.getTargetType(), grant.targetId());
        linkService.requireEdit(target);
        if (request.access() != null) {
            String access = ShareLinkService.normaliseAccess(request.access());
            if (ShareLink.ACCESS_DOWNLOAD.equals(access) && !authz.hasPermission(PermissionCodes.SEA_SET_PERMISSION_LEVEL)) {
                throw SharingException.forbidden("SHARING_NOT_ALLOWED", "You can't allow downloads.");
            }
            grant.setAccess(access);
        }
        if (request.expiresAt() != null) {
            if (!request.expiresAt().isAfter(OffsetDateTime.now().plusMinutes(1))) {
                throw SharingException.badRequest("SHARING_EXPIRY_INVALID", "The end date must be in the future.");
            }
            grant.setExpiresAt(request.expiresAt());
        }
        grantRepository.save(grant);
        return grantView(grant, target.name());
    }

    /** Takes the guest's access to this item away at once (SEA-FR-005). */
    @Transactional
    public void revokeGrant(UUID grantId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        GuestGrant grant = findGrant(tenantId, grantId);
        Target target = targets.find(tenantId, grant.getTargetType(), grant.targetId()).orElse(null);
        if (target != null) {
            linkService.requireEdit(target);
        } else if (!authz.isTenantAdmin()) {
            throw new ResourceNotFoundException("GuestGrant", grantId);
        }
        if (grant.getRevokedAt() != null) {
            return;
        }
        grant.setRevokedAt(OffsetDateTime.now());
        grant.setRevokedBy(userId);
        grantRepository.save(grant);
        GuestAccount guest = guestRepository.findById(grant.getGuestId()).orElse(null);
        String me = linkService.displayName(userId);
        String name = target != null ? target.name() : null;
        eventLog.record(new ShareEventLog.Subject(tenantId, ShareEventLog.GUEST_GRANT_REVOKED, null, grant.getGuestId(),
                grant.getTargetType(), grant.targetId(), name, userId, me,
                (guest != null ? guest.getEmail() : "guest") + " - access removed"));
        eventLog.audit(AuditActionCatalog.GUEST_ACCESS_REVOKED, tenantId, AuditObjectTypes.GUEST_ACCOUNT, grant.getGuestId(),
                guest != null ? guest.getEmail() : null, true, me,
                Map.of("targetType", grant.getTargetType(), "targetId", grant.targetId()));
    }

    // ------------------------------------------------------------------ inactivity (alternate flow)

    /**
     * Switches off guests nobody has signed in for longer than the organisation's inactivity period. Returns how many.
     * Run by {@link GuestInactivitySweeper} for each tenant, with no one signed in (hence the explicit tenant).
     */
    @Transactional
    public int disableInactive(UUID tenantId) {
        TenantSharingSettings settings = settingsService.forTenant(tenantId);
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(settings.getGuestInactivityDays());
        List<GuestAccount> stale = guestRepository.findInactive(tenantId, cutoff);
        for (GuestAccount guest : stale) {
            guest.setStatus(GuestAccount.DISABLED);
            guest.setDisabledReason(GuestAccount.DISABLED_INACTIVE);
            guestRepository.save(guest);
            eventLog.record(guestEvent(tenantId, ShareEventLog.GUEST_DISABLED, guest, null, "System",
                    "no sign-in for " + settings.getGuestInactivityDays() + " days"));
            eventLog.audit(AuditActionCatalog.GUEST_DISABLED, tenantId, AuditObjectTypes.GUEST_ACCOUNT,
                    guest.getGuestId(), guest.getEmail(), false, "System", Map.of("reason", "inactivity"));
        }
        return stale.size();
    }

    // ------------------------------------------------------------------ internals

    private GuestAccount createGuest(UUID tenantId, String email, String displayName) {
        String me = linkService.displayName(TenantContext.getUserId());
        GuestAccount guest = guestRepository.save(GuestAccount.builder().tenantId(tenantId).email(email)
                .displayName(clean(displayName)).createdBy(TenantContext.getUserId()).build());
        eventLog.record(guestEvent(tenantId, ShareEventLog.GUEST_INVITED, guest, TenantContext.getUserId(), me,
                "invited by " + me));
        eventLog.audit(AuditActionCatalog.GUEST_INVITED, tenantId, AuditObjectTypes.GUEST_ACCOUNT, guest.getGuestId(),
                email, true, me, Map.of());
        return guest;
    }

    /** New invitation token (the old one stops working) and the email that carries it. */
    private void sendInvite(GuestAccount guest) {
        String token = tokens.newToken();
        guest.setInviteTokenHash(tokens.hash(token));
        guest.setInviteExpiresAt(OffsetDateTime.now().plusDays(INVITE_DAYS));
        guestRepository.save(guest);
        mailer.sendInvite(guest.getEmail(), linkService.displayName(TenantContext.getUserId()),
                linkService.organisationName(guest.getTenantId()), mailer.inviteUrl(guest.getTenantId(), token));
    }

    private void requireEnabled(UUID tenantId) {
        if (!settingsService.forTenant(tenantId).isExternalSharingEnabled()) {
            throw SharingException.forbidden("SHARING_DISABLED", "External sharing is turned off for your organisation.");
        }
    }

    private static String requireEmail(String email) {
        String address = ShareLinkService.cleanEmail(email);
        if (address == null) {
            throw SharingException.badRequest("SHARING_EMAIL_INVALID", "Enter the guest's email address.");
        }
        return address;
    }

    private GuestAccount find(UUID tenantId, UUID guestId) {
        return guestRepository.findByGuestIdAndTenantId(guestId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Guest", guestId));
    }

    private GuestGrant findGrant(UUID tenantId, UUID grantId) {
        return grantRepository.findByGrantIdAndTenantId(grantId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("GuestGrant", grantId));
    }

    private Guest view(GuestAccount g) {
        return new Guest(g.getGuestId(), g.getEmail(), g.getDisplayName(), g.getStatus(), g.getDisabledReason(),
                g.getLastLoginAt(), g.getCreatedAt(), grantRepository.countByGuestIdAndRevokedAtIsNull(g.getGuestId()));
    }

    private GuestGrantView grantView(GuestGrant g, String targetName) {
        GuestAccount guest = guestRepository.findById(g.getGuestId()).orElseThrow();
        return new GuestGrantView(g.getGrantId(), g.getGuestId(), guest.getEmail(), guest.getDisplayName(),
                guest.getStatus(), g.getTargetType(), g.targetId(), targetName, g.getAccess(), g.getExpiresAt(),
                g.getRevokedAt(), g.getCreatedAt());
    }

    private static ShareEventLog.Subject guestEvent(UUID tenantId, String type, GuestAccount guest, UUID actorUserId,
                                                    String actor, String detail) {
        return new ShareEventLog.Subject(tenantId, type, null, guest.getGuestId(), null, null, null, actorUserId, actor,
                guest.getEmail() + " - " + detail);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return v.length() > 200 ? v.substring(0, 200) : v;
    }
}
