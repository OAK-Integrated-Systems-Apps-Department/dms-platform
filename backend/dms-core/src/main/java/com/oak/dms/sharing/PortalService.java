package com.oak.dms.sharing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.Folder;
import com.oak.dms.sharing.ShareBrowser.Entry;
import com.oak.dms.sharing.ShareBrowser.Served;
import com.oak.dms.sharing.ShareTargets.FolderTree;
import com.oak.dms.sharing.ShareTargets.Target;
import com.oak.dms.sharing.SharingDtos.Crumb;
import com.oak.dms.sharing.SharingDtos.InviteInfo;
import com.oak.dms.sharing.SharingDtos.Organisation;
import com.oak.dms.sharing.SharingDtos.PortalItem;
import com.oak.dms.sharing.SharingDtos.PortalListing;
import com.oak.dms.sharing.SharingDtos.PortalLogin;
import com.oak.dms.sharing.SharingDtos.PortalMe;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The client portal, from the guest's side (SEA-FR-004): accept an invitation, sign in, browse what has been shared,
 * open or download it. There is no Keycloak and no session here - a guest signs in with the password they set and is
 * given a signed token ({@link SharingTokens#mintGuest}) that is re-checked on every call against the account's status
 * and the live grants, so switching a guest off or revoking a grant takes effect on their very next request.
 *
 * <h2>Reach</h2>
 * A grant on a folder covers the folder and everything below it; a grant on a document covers that document. What is
 * inside a folder is subject to the same item rules as a share link ({@link ShareTargets}): confidential, held or
 * archived documents never show up, whatever the grant says - except the item the grant itself names, when an
 * administrator accepted the confidential override.
 */
@Service
@RequiredArgsConstructor
public class PortalService {

    static final int MIN_PASSWORD = 10;

    private final GuestAccountRepository guestRepository;
    private final GuestGrantRepository grantRepository;
    private final ShareTargets targets;
    private final ShareBrowser browser;
    private final ShareEventLog eventLog;
    private final SharingSettingsService settingsService;
    private final SharingTokens tokens;
    private final TenantRepository tenantRepository;
    private final DocumentRepository documentRepository;

    // ------------------------------------------------------------------ invitation

    @Transactional(readOnly = true)
    public InviteInfo inviteInfo(String token) {
        return byInvite(token).map(g -> new InviteInfo(true, g.getEmail(), organisationName(g.getTenantId())))
                .orElse(new InviteInfo(false, null, null));
    }

    @Transactional
    public void acceptInvite(String token, String password) {
        GuestAccount guest = byInvite(token).orElseThrow(() -> SharingException.badRequest("INVITE_INVALID",
                "This invitation is no longer valid. Ask the person who invited you to send a new one."));
        if (password == null || password.length() < MIN_PASSWORD
                || password.getBytes(StandardCharsets.UTF_8).length > SharingTokens.MAX_PASSWORD_BYTES) {
            throw SharingException.badRequest("PASSWORD_INVALID", "Choose a password of at least " + MIN_PASSWORD
                    + " characters.");
        }
        guest.setPasswordHash(tokens.hashPassword(password));
        guest.setStatus(GuestAccount.ACTIVE);
        guest.setInviteTokenHash(null);
        guest.setInviteExpiresAt(null);
        guest.setFailedAttempts(0);
        guest.setLockedUntil(null);
        guest.setLastLoginAt(OffsetDateTime.now());
        guestRepository.save(guest);
    }

    // ------------------------------------------------------------------ sign-in

    /**
     * Checks an email and password. The same address can be a guest of several organisations; if the password fits
     * more than one and no organisation was named, the guest is asked which (after the password has been proven, so
     * the list leaks nothing). Wrong passwords count against the account, and too many lock it for a while.
     */
    @Transactional
    public PortalLogin login(String email, String password, UUID tenantId) {
        String address = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now();
        List<GuestAccount> candidates = guestRepository.findByEmail(address).stream()
                .filter(g -> tenantId == null || g.getTenantId().equals(tenantId))
                .filter(g -> settingsService.forTenant(g.getTenantId()).isExternalSharingEnabled())
                .toList();
        if (candidates.isEmpty()) {
            tokens.matches(password, null); // same cost as a real check, so "no such guest" isn't detectable by timing
            return failed("invalid");
        }
        List<GuestAccount> matched = new ArrayList<>();
        boolean anyLocked = false;
        boolean anyDisabled = false;
        for (GuestAccount guest : candidates) {
            if (guest.getLockedUntil() != null && guest.getLockedUntil().isAfter(now)) {
                anyLocked = true;
                continue;
            }
            boolean usable = guest.getPasswordHash() != null && !GuestAccount.INVITED.equals(guest.getStatus());
            if (tokens.matches(password, usable ? guest.getPasswordHash() : null)) {
                guest.setFailedAttempts(0);
                if (GuestAccount.DISABLED.equals(guest.getStatus())) {
                    anyDisabled = true; // said only AFTER the password was right, so it reveals nothing to a guesser
                } else {
                    matched.add(guest);
                }
            } else if (usable) {
                int failures = guest.getFailedAttempts() + 1;
                TenantSharingSettings settings = settingsService.forTenant(guest.getTenantId());
                if (failures >= settings.getMaxPasswordAttempts()) {
                    guest.setFailedAttempts(0);
                    guest.setLockedUntil(now.plusMinutes(settings.getLockoutMinutes()));
                    anyLocked = true;
                } else {
                    guest.setFailedAttempts(failures);
                }
            }
            guestRepository.save(guest);
        }
        if (matched.isEmpty()) {
            return failed(anyDisabled ? "disabled" : anyLocked ? "locked" : "invalid");
        }
        if (matched.size() > 1) {
            return new PortalLogin(false, "choose_organisation", null, null, matched.stream()
                    .map(g -> new Organisation(g.getTenantId(), organisationName(g.getTenantId()))).toList());
        }
        GuestAccount guest = matched.get(0);
        guest.setLastLoginAt(now);
        guestRepository.save(guest);
        eventLog.record(new ShareEventLog.Subject(guest.getTenantId(), ShareEventLog.GUEST_SIGNED_IN, null,
                guest.getGuestId(), null, null, null, null, guest.getEmail(), null));
        eventLog.audit(AuditActionCatalog.GUEST_SIGNED_IN, guest.getTenantId(), AuditObjectTypes.GUEST_ACCOUNT,
                guest.getGuestId(), guest.getEmail(), false, guest.getEmail(), Map.of());
        SharingTokens.Signed token = tokens.mintGuest(guest.getTenantId(), guest.getGuestId());
        return new PortalLogin(true, "ok", token.token(), token.expiresInSeconds(), null);
    }

    // ------------------------------------------------------------------ browse

    @Transactional(readOnly = true)
    public PortalMe me(String guestToken) {
        GuestAccount guest = requireGuest(guestToken);
        return new PortalMe(guest.getEmail(), guest.getDisplayName(), organisationName(guest.getTenantId()));
    }

    /** {@code parentFolderId} null = what the guest was given; otherwise the contents of that folder. */
    @Transactional(readOnly = true)
    public PortalListing items(String guestToken, UUID parentFolderId) {
        GuestAccount guest = requireGuest(guestToken);
        UUID tenantId = guest.getTenantId();
        FolderTree tree = targets.tree(tenantId);
        List<Reach> reaches = reaches(guest, tree);
        if (parentFolderId == null) {
            List<PortalItem> roots = new ArrayList<>();
            for (Reach reach : reaches) {
                Target target = reach.target();
                if (target.isDocument()) {
                    browser.documentEntry(tenantId, target.document()).ifPresent(e -> roots.add(item(e, reach.grant())));
                } else {
                    roots.add(new PortalItem(ShareLink.TYPE_FOLDER, target.id(), target.name(), null, null,
                            target.folder().getUpdatedAt(), reach.grant().getAccess()));
                }
            }
            roots.sort(Comparator.comparing((PortalItem i) -> !ShareLink.TYPE_FOLDER.equals(i.type()))
                    .thenComparing(PortalItem::name, String.CASE_INSENSITIVE_ORDER));
            return new PortalListing(List.of(), roots);
        }
        Reach reach = coveringFolder(reaches, tree, parentFolderId)
                .orElseThrow(() -> new ResourceNotFoundException("Folder", parentFolderId));
        UUID root = reach.target().id();
        Folder folder = tree.get(parentFolderId).orElseThrow(() -> new ResourceNotFoundException("Folder", parentFolderId));
        if (!parentFolderId.equals(root) && !targets.servableBelow(folder, tree, root)) {
            throw new ResourceNotFoundException("Folder", parentFolderId);
        }
        List<PortalItem> items = browser.children(tenantId, tree, parentFolderId, root).stream()
                .map(e -> item(e, reach.grant())).toList();
        return new PortalListing(browser.breadcrumb(tree, root, parentFolderId), items);
    }

    @Transactional
    public Served content(String guestToken, UUID documentId, boolean download) {
        GuestAccount guest = requireGuest(guestToken);
        UUID tenantId = guest.getTenantId();
        FolderTree tree = targets.tree(tenantId);
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        GuestGrant grant = bestGrantFor(guest, document, tree)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        if (download && !grant.allowsDownload()) {
            throw SharingException.forbidden("SHARE_DOWNLOAD_NOT_ALLOWED", "Your access doesn't include downloading.");
        }
        Served served = browser.serve(tenantId, guest.getCreatedBy() != null ? guest.getCreatedBy() : guest.getGuestId(), documentId, download);
        eventLog.record(new ShareEventLog.Subject(tenantId,
                download ? ShareEventLog.GUEST_DOWNLOADED : ShareEventLog.GUEST_VIEWED, null, guest.getGuestId(),
                ShareLink.TYPE_DOCUMENT, documentId, document.getTitle(), null, guest.getEmail(), null));
        if (download) {
            eventLog.audit(AuditActionCatalog.GUEST_DOWNLOADED, tenantId, AuditObjectTypes.DOCUMENT, documentId,
                    document.getTitle(), false, guest.getEmail(), Map.of("guestId", guest.getGuestId()));
        }
        return served;
    }

    // ------------------------------------------------------------------ reach

    /** A live, servable grant and what it points at. */
    private record Reach(GuestGrant grant, Target target) {
    }

    private List<Reach> reaches(GuestAccount guest, FolderTree tree) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Reach> reaches = new ArrayList<>();
        for (GuestGrant grant : grantRepository.findByGuestIdAndRevokedAtIsNull(guest.getGuestId())) {
            if (!grant.isLive(now)) {
                continue;
            }
            boolean accepted = grant.getOverrideReason() != null;
            targets.find(guest.getTenantId(), grant.getTargetType(), grant.targetId())
                    .filter(t -> t.isDocument() ? targets.servableRoot(t.document(), tree, accepted)
                            : targets.servableRoot(t.folder(), tree, accepted))
                    .ifPresent(t -> reaches.add(new Reach(grant, t)));
        }
        return reaches;
    }

    /** The folder grant that covers {@code folderId} - the most generous one if several do. */
    private Optional<Reach> coveringFolder(List<Reach> reaches, FolderTree tree, UUID folderId) {
        return reaches.stream().filter(r -> !r.target().isDocument() && tree.within(folderId, r.target().id()))
                .max(Comparator.comparing((Reach r) -> r.grant().allowsDownload()));
    }

    private Optional<GuestGrant> bestGrantFor(GuestAccount guest, Document document, FolderTree tree) {
        return reaches(guest, tree).stream().filter(r -> r.target().isDocument()
                        ? r.target().id().equals(document.getDocumentId())
                        : document.getFolderId() != null && tree.within(document.getFolderId(), r.target().id())
                        && targets.servableBelow(document, tree, r.target().id()))
                .map(Reach::grant)
                .max(Comparator.comparing(GuestGrant::allowsDownload));
    }

    private GuestAccount requireGuest(String guestToken) {
        SharingTokens.GuestClaim claim = tokens.verifyGuest(guestToken).orElseThrow(() -> denied(
                "PORTAL_SESSION_EXPIRED", "Your session has ended. Sign in again."));
        GuestAccount guest = guestRepository.findByGuestIdAndTenantId(claim.guestId(), claim.tenantId())
                .orElseThrow(() -> denied("PORTAL_ACCESS_DENIED", "Your access is no longer available."));
        if (!GuestAccount.ACTIVE.equals(guest.getStatus())
                || !settingsService.forTenant(guest.getTenantId()).isExternalSharingEnabled()) {
            throw denied("PORTAL_ACCESS_DENIED", "Your access is no longer available.");
        }
        return guest;
    }

    private Optional<GuestAccount> byInvite(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return guestRepository.findByInviteTokenHash(tokens.hash(token.strip()))
                .filter(g -> GuestAccount.INVITED.equals(g.getStatus()))
                .filter(g -> g.getInviteExpiresAt() != null && g.getInviteExpiresAt().isAfter(OffsetDateTime.now()));
    }

    private String organisationName(UUID tenantId) {
        return tenantRepository.findByTenantIdAndDeletedFalse(tenantId).map(Tenant::getTenantName).orElse(null);
    }

    private static PortalItem item(Entry e, GuestGrant grant) {
        return new PortalItem(e.type(), e.id(), e.name(), e.mimeType(), e.sizeBytes(), e.updatedAt(), grant.getAccess());
    }

    private static PortalLogin failed(String status) {
        return new PortalLogin(false, status, null, null, null);
    }

    private static SharingException denied(String code, String message) {
        return new SharingException(HttpStatus.FORBIDDEN, code, message);
    }
}
