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
import com.oak.dms.sharing.SharingDtos.LinkInfo;
import com.oak.dms.sharing.SharingDtos.ListedItem;
import com.oak.dms.sharing.SharingDtos.Listing;
import com.oak.dms.sharing.SharingDtos.OpenResult;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The recipient's side of an external share link: no login, so every method starts from the token in the URL and
 * re-reads the link from the database - nothing is cached or trusted from an earlier call.
 *
 * <h2>The flow</h2>
 * {@link #info} (what is this link, is it usable - always answers, SEA-NFR-003) -&gt; {@link #open} (the password
 * check, one counted view, a 30-minute access token) -&gt; {@link #items} / {@link #content} (guarded by that
 * token AND by the link still being live, so a revoke or expiry stops access on the very next request).
 *
 * <h2>Denied is a page, not an error</h2>
 * {@code info} and {@code open} answer 200 with a {@code status}; only the content routes use HTTP errors, because
 * a browser frame can't show a JSON status.
 *
 * <h2>What "unavailable" covers</h2>
 * Besides unknown tokens: a link an administrator hasn't yet reviewed after the item became confidential, sharing
 * switched off for the organisation, and a target that is gone, held, archived, or confidential without an accepted
 * override. All look the same from outside - the recipient is told "not available", never why.
 */
@Service
@RequiredArgsConstructor
public class PublicShareService {

    private final ShareLinkRepository linkRepository;
    private final ShareEventRepository eventRepository;
    private final ShareTargets targets;
    private final ShareBrowser browser;
    private final ShareEventLog eventLog;
    private final SharingSettingsService settingsService;
    private final SharingTokens tokens;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;

    // ------------------------------------------------------------------ info

    @Transactional(readOnly = true)
    public LinkInfo info(String token) {
        ShareLink link = linkRepository.findByTokenHash(tokens.hash(clean(token))).orElse(null);
        if (link == null) {
            return LinkInfo.denied("unavailable");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String status = link.status(now);
        if (!"active".equals(status)) {
            return new LinkInfo(status, false, null, null, null, null, null, null,
                    "locked".equals(status) ? link.getLockedUntil() : null);
        }
        Target target = usableTarget(link, targets.tree(link.getTenantId())).orElse(null);
        if (target == null) {
            return LinkInfo.denied("unavailable");
        }
        String organisation = tenantRepository.findByTenantIdAndDeletedFalse(link.getTenantId())
                .map(Tenant::getTenantName).orElse(null);
        String sharedBy = userRepository.findById(link.getCreatedBy()).map(ShareLinkService::nameOf).orElse(null);
        return new LinkInfo("active", link.getPasswordHash() != null, link.getTargetType(), target.name(),
                link.getAccess(), link.getExpiresAt(), organisation, sharedBy, null);
    }

    // ------------------------------------------------------------------ open

    /**
     * Opens the link: checks the password if there is one, counts the view and hands back an access token. The link
     * row is locked for the duration so two recipients can't both use up the last view, and a script can't try
     * passwords in parallel past the attempt limit.
     */
    @Transactional
    public OpenResult open(String token, String password) {
        ShareLink link = linkRepository.lockByTokenHash(tokens.hash(clean(token))).orElse(null);
        if (link == null) {
            return denied("unavailable");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String status = link.status(now);
        if ("expired".equals(status) || "exhausted".equals(status)) {
            recordEndOnce(link);
            return denied(status);
        }
        if ("revoked".equals(status)) {
            return denied(status);
        }
        if ("locked".equals(status)) {
            return new OpenResult(false, "locked", 0, link.getLockedUntil(), null, null);
        }
        Target target = usableTarget(link, targets.tree(link.getTenantId())).orElse(null);
        if (target == null) {
            return denied("unavailable");
        }
        TenantSharingSettings settings = settingsService.forTenant(link.getTenantId());

        if (link.getPasswordHash() != null) {
            if (password == null || password.isEmpty()) {
                // Nothing was tried yet (the page asked before the recipient typed): not an attempt.
                return new OpenResult(false, "wrong_password", Math.max(settings.getMaxPasswordAttempts()
                        - link.getFailedAttempts(), 0), null, null, null);
            }
            if (!tokens.matches(password, link.getPasswordHash())) {
                return wrongPassword(link, target, settings, now);
            }
            link.setFailedAttempts(0);
        }

        link.setViewCount(link.getViewCount() + 1);
        link.setLastAccessedAt(now);
        linkRepository.save(link);
        String who = link.getRecipientEmail() != null ? link.getRecipientEmail() : "Anonymous";
        eventLog.record(new ShareEventLog.Subject(link.getTenantId(), ShareEventLog.LINK_OPENED, link.getLinkId(), null,
                link.getTargetType(), link.targetId(), target.name(), null, who,
                "view " + link.getViewCount() + (link.getMaxViews() != null ? " of " + link.getMaxViews() : "")));
        eventLog.audit(AuditActionCatalog.SHARE_LINK_ACCESSED, link.getTenantId(), AuditObjectTypes.SHARE_LINK,
                link.getLinkId(), target.name(), false, who, Map.of("targetType", link.getTargetType()));
        SharingTokens.Signed access = tokens.mintLinkAccess(link.getLinkId());
        return new OpenResult(true, "active", null, null, access.token(), access.expiresInSeconds());
    }

    private OpenResult wrongPassword(ShareLink link, Target target, TenantSharingSettings settings,
                                     OffsetDateTime now) {
        int failed = link.getFailedAttempts() + 1;
        String who = link.getRecipientEmail() != null ? link.getRecipientEmail() : "Anonymous";
        eventLog.record(new ShareEventLog.Subject(link.getTenantId(), ShareEventLog.PASSWORD_FAILED, link.getLinkId(),
                null, link.getTargetType(), link.targetId(), target.name(), null, who,
                "attempt " + failed + " of " + settings.getMaxPasswordAttempts()));
        if (failed >= settings.getMaxPasswordAttempts()) {
            // Too many wrong passwords (alternate flow): block further attempts on THIS link for a while.
            link.setFailedAttempts(0);
            link.setLockedUntil(now.plusMinutes(settings.getLockoutMinutes()));
            linkRepository.save(link);
            eventLog.record(new ShareEventLog.Subject(link.getTenantId(), ShareEventLog.LINK_LOCKED, link.getLinkId(),
                    null, link.getTargetType(), link.targetId(), target.name(), null, who,
                    "locked for " + settings.getLockoutMinutes() + " minutes"));
            return new OpenResult(false, "locked", 0, link.getLockedUntil(), null, null);
        }
        link.setFailedAttempts(failed);
        linkRepository.save(link);
        return new OpenResult(false, "wrong_password", settings.getMaxPasswordAttempts() - failed, null, null, null);
    }

    // ------------------------------------------------------------------ browse and read

    @Transactional(readOnly = true)
    public Listing items(String token, String accessToken, UUID folderId) {
        ShareLink link = liveLink(token, accessToken);
        if (link.isDocument()) {
            throw SharingException.badRequest("SHARE_NOT_A_FOLDER", "This link is to a single document.");
        }
        UUID tenantId = link.getTenantId();
        FolderTree tree = targets.tree(tenantId);
        UUID root = link.getFolderId();
        UUID current = folderId == null ? root : folderId;
        Folder folder = tree.get(current).orElseThrow(() -> new ResourceNotFoundException("Folder", current));
        if (!tree.within(current, root) || (!current.equals(root) && !targets.servableBelow(folder, tree, root))) {
            throw new ResourceNotFoundException("Folder", current);
        }
        List<Crumb> crumbs = browser.breadcrumb(tree, root, current);
        List<ListedItem> items = browser.children(tenantId, tree, current, root).stream().map(PublicShareService::item).toList();
        return new Listing(new Crumb(folder.getFolderId(), folder.getFolderName()), crumbs, items);
    }

    /**
     * The file: shown in the browser, or downloaded. Records the view or download and checks the link's own rights
     * (a view-only link can't download, whatever the page asks for).
     */
    @Transactional
    public Served content(String token, String accessToken, UUID documentId, boolean download) {
        ShareLink link = liveLink(token, accessToken);
        if (download && !link.allowsDownload()) {
            throw SharingException.forbidden("SHARE_DOWNLOAD_NOT_ALLOWED", "This link doesn't allow downloads.");
        }
        UUID tenantId = link.getTenantId();
        Document document = documentFor(link, tenantId, documentId, targets.tree(tenantId));
        Served served = browser.serve(tenantId, link.getCreatedBy(), document.getDocumentId(), download);
        String who = link.getRecipientEmail() != null ? link.getRecipientEmail() : "Anonymous";
        eventLog.record(new ShareEventLog.Subject(tenantId,
                download ? ShareEventLog.LINK_DOWNLOADED : ShareEventLog.LINK_VIEWED, link.getLinkId(), null,
                ShareLink.TYPE_DOCUMENT, document.getDocumentId(), document.getTitle(), null, who, null));
        if (download) {
            eventLog.audit(AuditActionCatalog.SHARE_LINK_DOWNLOADED, tenantId, AuditObjectTypes.DOCUMENT,
                    document.getDocumentId(), document.getTitle(), false, who, Map.of("linkId", link.getLinkId()));
        }
        return served;
    }

    // ------------------------------------------------------------------ internals

    /** The link behind both tokens, checked live - or 403 with the reason code the page understands. */
    private ShareLink liveLink(String token, String accessToken) {
        UUID linkId = tokens.verifyLinkAccess(accessToken).orElseThrow(() -> unavailable("SHARE_ACCESS_EXPIRED",
                "Your access has timed out. Open the link again."));
        ShareLink link = linkRepository.findByTokenHash(tokens.hash(clean(token)))
                .filter(l -> l.getLinkId().equals(linkId))
                .orElseThrow(() -> unavailable("SHARE_UNAVAILABLE", "This link is not available."));
        OffsetDateTime now = OffsetDateTime.now();
        String status = link.status(now);
        // "exhausted" still serves: the view that used the last allowance is the one reading the content now.
        if ("revoked".equals(status) || "expired".equals(status)) {
            throw unavailable("SHARE_" + status.toUpperCase(), "This link is " + status + ".");
        }
        if (usableTarget(link, targets.tree(link.getTenantId())).isEmpty()) {
            throw unavailable("SHARE_UNAVAILABLE", "This link is not available.");
        }
        return link;
    }

    /** Whether the link's target can be shared right now, and the target if so. */
    private java.util.Optional<Target> usableTarget(ShareLink link, FolderTree tree) {
        if (link.isNeedsReview() || !settingsService.forTenant(link.getTenantId()).isExternalSharingEnabled()) {
            return java.util.Optional.empty();
        }
        boolean accepted = link.getOverrideReason() != null;
        return targets.find(link.getTenantId(), link.getTargetType(), link.targetId()).filter(t -> t.isDocument()
                ? targets.servableRoot(t.document(), tree, accepted)
                : targets.servableRoot(t.folder(), tree, accepted));
    }

    /** The document {@code documentId} within what the link covers - the shared document itself, or one inside the shared folder. */
    private Document documentFor(ShareLink link, UUID tenantId, UUID documentId, FolderTree tree) {
        if (link.isDocument()) {
            if (documentId != null && !documentId.equals(link.getDocumentId())) {
                throw new ResourceNotFoundException("Document", documentId);
            }
            return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(link.getDocumentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Document", link.getDocumentId()));
        }
        if (documentId == null) {
            throw SharingException.badRequest("SHARE_DOCUMENT_REQUIRED", "Say which document to open.");
        }
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        if (document.getFolderId() == null || !tree.within(document.getFolderId(), link.getFolderId())
                || !targets.servableBelow(document, tree, link.getFolderId())) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        return document;
    }

    /** The automatic invalidation (workflow step 5) is logged the first time someone meets it. */
    private void recordEndOnce(ShareLink link) {
        if (eventRepository.existsByLinkIdAndEventType(link.getLinkId(), ShareEventLog.LINK_EXPIRED)) {
            return;
        }
        eventLog.record(new ShareEventLog.Subject(link.getTenantId(), ShareEventLog.LINK_EXPIRED, link.getLinkId(), null,
                link.getTargetType(), link.targetId(), null, null, "System",
                link.getMaxViews() != null && link.getViewCount() >= link.getMaxViews()
                        ? "view limit reached" : "expiry reached"));
    }

    private static OpenResult denied(String status) {
        return new OpenResult(false, status, null, null, null, null);
    }

    private static ListedItem item(Entry e) {
        return new ListedItem(e.type(), e.id(), e.name(), e.mimeType(), e.sizeBytes(), e.updatedAt());
    }

    private static SharingException unavailable(String code, String message) {
        return new SharingException(HttpStatus.FORBIDDEN, code, message);
    }

    private static String clean(String token) {
        return token == null ? "" : token.strip();
    }
}
