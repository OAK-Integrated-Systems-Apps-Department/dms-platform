package com.oak.dms.sharing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.sharing.ShareTargets.FolderTree;
import com.oak.dms.sharing.ShareTargets.Target;
import com.oak.dms.sharing.SharingDtos.CreateLink;
import com.oak.dms.sharing.SharingDtos.Eligibility;
import com.oak.dms.sharing.SharingDtos.Link;
import com.oak.dms.sharing.SharingDtos.LinkCreated;
import com.oak.dms.sharing.SharingDtos.Page;
import com.oak.dms.sharing.SharingDtos.ShareEventView;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creating, listing and revoking external share links (SEA-FR-001, -002, -003, -005, -007), as the signed-in user.
 * The recipient's side is {@link PublicShareService}.
 *
 * <h2>Who may do what</h2>
 * Sharing something needs <b>edit</b> access to it (the same level that lets you change it) on top of the SEA
 * permission code; tenant administrators pass both everywhere. Setting an expiry, a view limit or a password needs
 * {@code sea.create-external-share-links.set-expiry-password}, and "view and download" needs
 * {@code ...set-permission-level} - without them a link is view-only with the organisation's default expiry.
 * Revoking is for whoever made the link, a tenant administrator, or anyone who manages the item.
 */
@Service
@RequiredArgsConstructor
public class ShareLinkService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_PASSWORD = 64;
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_VIEWS_LIMIT = 100_000;

    private final ShareLinkRepository linkRepository;
    private final ShareEventRepository eventRepository;
    private final ShareTargets targets;
    private final ShareEventLog eventLog;
    private final SharingSettingsService settingsService;
    private final SharingTokens tokens;
    private final SharingMailer mailer;
    private final AuthorizationService authz;
    private final DocumentAccessService documentAccess;
    private final FolderAccessService folderAccess;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;

    // ------------------------------------------------------------------ eligibility

    @Transactional(readOnly = true)
    public Eligibility eligibility(String targetType, UUID targetId) {
        UUID tenantId = TenantContext.getTenantId();
        Target target = targets.resolve(tenantId, ShareTargets.normaliseType(targetType), targetId);
        requireEdit(target);
        return targets.eligibility(target, targets.tree(tenantId),
                settingsService.forTenant(tenantId).isExternalSharingEnabled(), authz.isTenantAdmin());
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public LinkCreated create(CreateLink request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        TenantSharingSettings settings = settingsService.forTenant(tenantId);
        if (!settings.isExternalSharingEnabled()) {
            throw SharingException.forbidden("SHARING_DISABLED", "External sharing is turned off for your organisation.");
        }
        String type = ShareTargets.normaliseType(request.targetType());
        Target target = targets.resolve(tenantId, type, request.targetId());
        requireEdit(target);

        String access = normaliseAccess(request.access());
        if (ShareLink.ACCESS_DOWNLOAD.equals(access) && !authz.hasPermission(PermissionCodes.SEA_SET_PERMISSION_LEVEL)) {
            throw SharingException.forbidden("SHARING_NOT_ALLOWED", "You can share view-only links; ask an administrator "
                    + "for permission to allow downloads.");
        }

        String overrideReason = checkEligibility(target, tenantId, request.overrideRestriction(), request.overrideReason());

        boolean customised = request.expiresAt() != null || request.maxViews() != null
                || (request.password() != null && !request.password().isEmpty());
        if (customised && !authz.hasPermission(PermissionCodes.SEA_SET_EXPIRY_PASSWORD)) {
            throw SharingException.forbidden("SHARING_NOT_ALLOWED",
                    "You don't have permission to set an expiry, view limit or password on a link.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = expiry(request.expiresAt(), now, settings);
        Integer maxViews = request.maxViews();
        if (maxViews != null && (maxViews < 1 || maxViews > MAX_VIEWS_LIMIT)) {
            throw SharingException.badRequest("SHARING_VIEWS_INVALID", "The view limit must be between 1 and " + MAX_VIEWS_LIMIT + ".");
        }
        String passwordHash = null;
        if (request.password() != null && !request.password().isEmpty()) {
            if (request.password().length() < MIN_PASSWORD || request.password().length() > MAX_PASSWORD) {
                throw SharingException.badRequest("SHARING_PASSWORD_INVALID",
                        "The password must be " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters.");
            }
            passwordHash = tokens.hashPassword(request.password());
        }
        String recipient = cleanEmail(request.recipientEmail());

        String token = tokens.newToken();
        ShareLink link = linkRepository.save(ShareLink.builder()
                .tenantId(tenantId).targetType(type)
                .documentId(target.isDocument() ? target.id() : null)
                .folderId(target.isDocument() ? null : target.id())
                .tokenHash(tokens.hash(token)).access(access).passwordHash(passwordHash)
                .expiresAt(expiresAt).maxViews(maxViews).recipientEmail(recipient)
                .overrideReason(overrideReason).createdBy(userId).build());

        String me = displayName(userId);
        eventLog.record(new ShareEventLog.Subject(tenantId, ShareEventLog.LINK_CREATED, link.getLinkId(), null, type,
                target.id(), target.name(), userId, me,
                access + (passwordHash != null ? ", password" : "") + (maxViews != null ? ", " + maxViews + " views" : "")
                        + (overrideReason != null ? ", administrator override" : "")));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetType", type);
        details.put("access", access);
        details.put("expiresAt", expiresAt.toString());
        details.put("passwordProtected", passwordHash != null);
        details.put("maxViews", maxViews);
        details.put("recipient", recipient);
        details.put("override", overrideReason);
        eventLog.audit(AuditActionCatalog.SHARE_LINK_CREATED, tenantId, AuditObjectTypes.SHARE_LINK, link.getLinkId(),
                target.name(), true, me, details);

        String url = mailer.linkUrl(token);
        boolean emailSent = recipient != null && mailer.sendLink(recipient, me, target.name(), organisationName(tenantId),
                url, expiresAt, passwordHash != null, ShareLink.ACCESS_DOWNLOAD.equals(access), request.message());
        return new LinkCreated(view(link, target.name(), Map.of(userId, me)), url, emailSent);
    }

    // ------------------------------------------------------------------ read

    /** The links on one document or folder, newest first. */
    @Transactional(readOnly = true)
    public List<Link> listFor(String targetType, UUID targetId) {
        UUID tenantId = TenantContext.getTenantId();
        Target target = targets.resolve(tenantId, ShareTargets.normaliseType(targetType), targetId);
        requireEdit(target);
        List<ShareLink> links = target.isDocument()
                ? linkRepository.findByTenantIdAndDocumentIdOrderByCreatedAtDesc(tenantId, targetId)
                : linkRepository.findByTenantIdAndFolderIdOrderByCreatedAtDesc(tenantId, targetId);
        Map<UUID, String> names = userNames(tenantId, links);
        return links.stream().map(l -> view(l, target.name(), names)).toList();
    }

    /** Every link in the organisation (tenant administrators). */
    @Transactional(readOnly = true)
    public Page<Link> all(String status, int page, int size) {
        UUID tenantId = TenantContext.getTenantId();
        String filter = switch (status == null ? "all" : status.toLowerCase(Locale.ROOT)) {
            case "active", "expired", "revoked", "review" -> status.toLowerCase(Locale.ROOT);
            default -> "all";
        };
        var result = linkRepository.findForTenant(tenantId, filter, OffsetDateTime.now(),
                PageRequest.of(Math.max(page, 0), clamp(size)));
        Map<UUID, String> names = userNames(tenantId, result.getContent());
        FolderTree tree = targets.tree(tenantId);
        List<Link> items = result.getContent().stream()
                .map(l -> view(l, nameOf(tenantId, l, tree), names)).toList();
        return new Page<>(items, result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @Transactional(readOnly = true)
    public Page<ShareEventView> events(String targetType, UUID targetId, UUID linkId, int page, int size) {
        UUID tenantId = TenantContext.getTenantId();
        boolean viewAll = authz.hasPermission(PermissionCodes.SEA_VIEW_ACTIVITY);
        var pageable = PageRequest.of(Math.max(page, 0), clamp(size));
        org.springframework.data.domain.Page<ShareEvent> result;
        if (linkId != null) {
            ShareLink link = findLink(tenantId, linkId);
            requireActivityAccess(viewAll, tenantId, link.getTargetType(), link.targetId());
            result = eventRepository.findByTenantIdAndLinkIdOrderByOccurredAtDesc(tenantId, linkId, pageable);
        } else if (targetId != null) {
            requireActivityAccess(viewAll, tenantId, ShareTargets.normaliseType(targetType), targetId);
            result = eventRepository.findByTenantIdAndTargetIdOrderByOccurredAtDesc(tenantId, targetId, pageable);
        } else {
            if (!viewAll) {
                throw SharingException.forbidden("SHARING_NOT_ALLOWED", "You can't view the organisation's sharing activity.");
            }
            result = eventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
        }
        return new Page<>(result.getContent().stream().map(ShareLinkService::view).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    // ------------------------------------------------------------------ revoke / review

    /** Stops the link at once (SEA-FR-005): the next request from the recipient gets the "revoked" page. */
    @Transactional
    public void revoke(UUID linkId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        ShareLink link = findLink(tenantId, linkId);
        Target target = targets.find(tenantId, link.getTargetType(), link.targetId()).orElse(null);
        if (!authz.isTenantAdmin() && !userId.equals(link.getCreatedBy()) && !(target != null && canManage(target))) {
            throw SharingException.forbidden("SHARING_NOT_ALLOWED",
                    "Only the person who made a link, someone who manages the item, or an administrator can revoke it.");
        }
        if (link.getRevokedAt() != null) {
            return;
        }
        link.setRevokedAt(OffsetDateTime.now());
        link.setRevokedBy(userId);
        link.setRevokeReason(ShareLink.REVOKED_MANUAL);
        link.setNeedsReview(false);
        linkRepository.save(link);
        String me = displayName(userId);
        String name = target != null ? target.name() : null;
        eventLog.record(new ShareEventLog.Subject(tenantId, ShareEventLog.LINK_REVOKED, linkId, null, link.getTargetType(),
                link.targetId(), name, userId, me, "revoked by " + me));
        eventLog.audit(AuditActionCatalog.SHARE_LINK_REVOKED, tenantId, AuditObjectTypes.SHARE_LINK, linkId, name, true, me,
                Map.of("targetType", link.getTargetType()));
    }

    /** An administrator reviewed a flagged link and decided to keep it. */
    @Transactional
    public void clearReview(UUID linkId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        ShareLink link = findLink(tenantId, linkId);
        if (!link.isNeedsReview()) {
            return;
        }
        link.setNeedsReview(false);
        // Kept on purpose, so the page serves it. Recorded like an override: an administrator accepted the exposure.
        if (link.getOverrideReason() == null) {
            link.setOverrideReason("Kept after review by " + displayName(userId));
        }
        linkRepository.save(link);
        Target target = targets.find(tenantId, link.getTargetType(), link.targetId()).orElse(null);
        String me = displayName(userId);
        String name = target != null ? target.name() : null;
        eventLog.record(new ShareEventLog.Subject(tenantId, ShareEventLog.LINK_REVIEW_CLEARED, linkId, null,
                link.getTargetType(), link.targetId(), name, userId, me, "kept after review"));
        eventLog.audit(AuditActionCatalog.SHARE_LINK_REVIEWED, tenantId, AuditObjectTypes.SHARE_LINK, linkId, name, true,
                me, Map.of("decision", "kept"));
    }

    // ------------------------------------------------------------------ shared helpers (also used by GuestService)

    /**
     * Applies SEA-FR-007 / SEA-NFR-005 to a share about to be made. Returns the administrator's override reason when
     * one was needed (and is allowed), null when the item is simply shareable.
     */
    String checkEligibility(Target target, UUID tenantId, Boolean overrideRequested, String reason) {
        boolean admin = authz.isTenantAdmin();
        Eligibility eligibility = targets.eligibility(target, targets.tree(tenantId), true, admin);
        if (eligibility.canShare()) {
            return null;
        }
        if (Boolean.TRUE.equals(overrideRequested)) {
            if (!admin) {
                throw SharingException.forbidden("SHARING_OVERRIDE_REQUIRES_ADMIN",
                        "Only a tenant administrator can share something confidential.");
            }
            if (!eligibility.canOverride()) {
                throw SharingException.forbidden("SHARING_NOT_ALLOWED", String.join(" ", eligibility.reasons()));
            }
            if (reason == null || reason.isBlank()) {
                throw SharingException.badRequest("SHARING_OVERRIDE_REASON_REQUIRED",
                        "Say why this confidential item may be shared.");
            }
            return reason.strip().length() > 500 ? reason.strip().substring(0, 500) : reason.strip();
        }
        throw SharingException.forbidden("SHARING_NOT_ALLOWED", String.join(" ", eligibility.reasons()));
    }

    /** The caller needs edit on the item (404 if they can't see it, 403 if they can only look). */
    void requireEdit(Target target) {
        if (target.isDocument()) {
            documentAccess.require(documentAccess.forCaller(), target.document(), FolderAccessLevel.edit);
        } else {
            folderAccess.require(target.folder().getFolderId(), FolderAccessLevel.edit);
        }
    }

    boolean canManage(Target target) {
        try {
            if (target.isDocument()) {
                documentAccess.require(documentAccess.forCaller(), target.document(), FolderAccessLevel.manage);
            } else {
                folderAccess.require(target.folder().getFolderId(), FolderAccessLevel.manage);
            }
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    static String normaliseAccess(String access) {
        if (access == null) {
            return ShareLink.ACCESS_VIEW;
        }
        String value = access.strip().toLowerCase(Locale.ROOT);
        if (!ShareLink.ACCESS_VIEW.equals(value) && !ShareLink.ACCESS_DOWNLOAD.equals(value)) {
            throw SharingException.badRequest("SHARING_ACCESS_INVALID", "Choose view only, or view and download.");
        }
        return value;
    }

    static String cleanEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String value = email.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 320 || !EMAIL.matcher(value).matches()) {
            throw SharingException.badRequest("SHARING_EMAIL_INVALID", "That email address doesn't look right.");
        }
        return value;
    }

    String displayName(UUID userId) {
        return userRepository.findById(userId).map(ShareLinkService::nameOf).orElse("A colleague");
    }

    String organisationName(UUID tenantId) {
        return tenantRepository.findByTenantIdAndDeletedFalse(tenantId).map(Tenant::getTenantName).orElse("Your organisation");
    }

    // ------------------------------------------------------------------ internals

    private OffsetDateTime expiry(OffsetDateTime requested, OffsetDateTime now, TenantSharingSettings settings) {
        if (requested == null) {
            return now.plusDays(settings.getDefaultExpiryDays());
        }
        if (!requested.isAfter(now.plusMinutes(1))) {
            throw SharingException.badRequest("SHARING_EXPIRY_INVALID", "The expiry must be in the future.");
        }
        if (requested.isAfter(now.plusDays(settings.getMaxExpiryDays()))) {
            throw SharingException.badRequest("SHARING_EXPIRY_TOO_LONG", "Your organisation allows links to last at most "
                    + settings.getMaxExpiryDays() + " days.");
        }
        return requested;
    }

    private void requireActivityAccess(boolean viewAll, UUID tenantId, String type, UUID targetId) {
        if (viewAll) {
            return;
        }
        Target target = targets.resolve(tenantId, type, targetId);
        requireEdit(target);
    }

    private ShareLink findLink(UUID tenantId, UUID linkId) {
        return linkRepository.findByLinkIdAndTenantId(linkId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("ShareLink", linkId));
    }

    private String nameOf(UUID tenantId, ShareLink link, FolderTree tree) {
        return targets.find(tenantId, link.getTargetType(), link.targetId()).map(Target::name).orElse("(deleted)");
    }

    private Map<UUID, String> userNames(UUID tenantId, List<ShareLink> links) {
        Set<UUID> ids = new HashSet<>();
        links.forEach(l -> ids.add(l.getCreatedBy()));
        Map<UUID, String> names = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            userRepository.findAllByUserIdInAndTenantIdAndDeletedFalse(ids, tenantId)
                    .forEach(u -> names.put(u.getUserId(), nameOf(u)));
        }
        return names;
    }

    static Link view(ShareLink l, String targetName, Map<UUID, String> userNames) {
        return new Link(l.getLinkId(), l.getTargetType(), l.targetId(), targetName, l.getAccess(),
                l.status(OffsetDateTime.now()), l.getPasswordHash() != null, l.getExpiresAt(), l.getMaxViews(),
                l.getViewCount(), l.getRecipientEmail(), l.getCreatedBy(),
                userNames.getOrDefault(l.getCreatedBy(), "Unknown"), l.getCreatedAt(), l.getLastAccessedAt(),
                l.isNeedsReview(), l.getOverrideReason() != null, l.getRevokedAt());
    }

    static ShareEventView view(ShareEvent e) {
        return new ShareEventView(e.getEventId(), e.getOccurredAt(), e.getEventType(), e.getLinkId(), e.getGuestId(),
                e.getActorLabel(), e.getIpAddress(), e.getTargetName(), e.getDetail());
    }

    static String nameOf(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getUsername() != null ? user.getUsername() : user.getEmail();
    }

    static int clamp(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    static HttpStatus notFound() {
        return HttpStatus.NOT_FOUND;
    }
}
