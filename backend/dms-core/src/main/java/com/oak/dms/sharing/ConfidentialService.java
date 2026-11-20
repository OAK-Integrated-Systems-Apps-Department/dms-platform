package com.oak.dms.sharing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.FolderRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.sharing.ShareTargets.FolderTree;
import com.oak.dms.sharing.ShareTargets.Target;
import com.oak.dms.sharing.SharingDtos.ConfidentialResult;
import com.oak.dms.sharing.SharingDtos.SetConfidential;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Marking a document or folder confidential (SEA-FR-007) - and what that does to what is already shared (alternate
 * flow: "shared, then reclassified as confidential").
 *
 * <p>Anyone who manages the item can mark it; only a tenant administrator can clear the mark again, since clearing
 * is what re-opens it to the outside. Marking applies the organisation's policy to everything already out there:
 * a share link is flagged for an administrator's review (and stops working until reviewed) or revoked outright
 * ({@code on_reclassify}); a guest's access to it is always revoked, because a grant is standing access with no
 * review step to wait in.
 */
@Service
@RequiredArgsConstructor
public class ConfidentialService {

    private static final UUID NONE = new UUID(0, 0);

    private final ShareTargets targets;
    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final ShareLinkRepository linkRepository;
    private final GuestGrantRepository grantRepository;
    private final ShareEventLog eventLog;
    private final SharingSettingsService settingsService;
    private final ShareLinkService linkService;
    private final AuthorizationService authz;
    private final AuditService auditService;

    @Transactional
    public ConfidentialResult set(SetConfidential request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String type = ShareTargets.normaliseType(request.targetType());
        Target target = targets.resolve(tenantId, type, request.targetId());
        boolean admin = authz.isTenantAdmin();
        if (!admin && !linkService.canManage(target)) {
            // Can't even see it -> 404 from requireEdit; can see it but not manage -> 403.
            linkService.requireEdit(target);
            throw SharingException.forbidden("SHARING_NOT_ALLOWED", "Only someone who manages this can change its confidentiality.");
        }
        boolean current = target.isDocument() ? target.document().isConfidential() : target.folder().isConfidential();
        if (current == request.confidential()) {
            return new ConfidentialResult(0, 0, 0);
        }
        if (!request.confidential() && !admin) {
            throw SharingException.forbidden("SHARING_NOT_ALLOWED", "Only a tenant administrator can remove the confidential mark.");
        }
        if (target.isDocument()) {
            target.document().setConfidential(request.confidential());
            documentRepository.save(target.document());
        } else {
            target.folder().setConfidential(request.confidential());
            folderRepository.save(target.folder());
        }
        String me = linkService.displayName(userId);
        auditService.event(AuditActionCatalog.CONFIDENTIAL_FLAG_CHANGED)
                .objectType(target.isDocument() ? AuditObjectTypes.DOCUMENT : AuditObjectTypes.FOLDER)
                .objectId(target.id()).resourceName(target.name()).outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("confidential", current))
                .newValue(Map.of("confidential", request.confidential()))
                .reason(request.reason())
                .log();
        return request.confidential() ? applyPolicy(tenantId, userId, me, target) : new ConfidentialResult(0, 0, 0);
    }

    /** Flags or revokes the live links, and revokes the guest grants, on the item and (for a folder) everything below. */
    private ConfidentialResult applyPolicy(UUID tenantId, UUID userId, String me, Target target) {
        FolderTree tree = targets.tree(tenantId);
        Set<UUID> folderIds = target.isDocument() ? Set.of() : tree.subtreeIds(target.id());
        Set<UUID> documentIds = new java.util.LinkedHashSet<>();
        if (target.isDocument()) {
            documentIds.add(target.id());
        } else if (!folderIds.isEmpty()) {
            documentIds.addAll(documentRepository.findIdsInFolders(tenantId, folderIds, List.of(NONE),
                    PageRequest.of(0, 100_000)));
        }

        List<ShareLink> links = new java.util.ArrayList<>();
        if (!documentIds.isEmpty()) {
            links.addAll(linkRepository.findLiveByDocuments(tenantId, documentIds));
        }
        if (!folderIds.isEmpty()) {
            links.addAll(linkRepository.findLiveByFolders(tenantId, folderIds));
        }
        boolean revoke = TenantSharingSettings.ON_RECLASSIFY_REVOKE.equals(settingsService.forTenant(tenantId).getOnReclassify());
        OffsetDateTime now = OffsetDateTime.now();
        int flagged = 0;
        int revoked = 0;
        for (ShareLink link : links) {
            String name = nameOf(tenantId, link, target);
            if (revoke) {
                link.setRevokedAt(now);
                link.setRevokedBy(userId);
                link.setRevokeReason(ShareLink.REVOKED_RECLASSIFIED);
                link.setNeedsReview(false);
                linkRepository.save(link);
                revoked++;
                eventLog.record(new ShareEventLog.Subject(tenantId, ShareEventLog.LINK_REVOKED, link.getLinkId(), null,
                        link.getTargetType(), link.targetId(), name, userId, me, "revoked: marked confidential"));
                eventLog.audit(AuditActionCatalog.SHARE_LINK_REVOKED, tenantId, AuditObjectTypes.SHARE_LINK,
                        link.getLinkId(), name, true, me, Map.of("reason", "marked confidential"));
            } else {
                link.setNeedsReview(true);
                // The earlier administrator override (if any) was for the item as it was; it has to be accepted again.
                link.setOverrideReason(null);
                linkRepository.save(link);
                flagged++;
                eventLog.record(new ShareEventLog.Subject(tenantId, ShareEventLog.LINK_FLAGGED, link.getLinkId(), null,
                        link.getTargetType(), link.targetId(), name, userId, me,
                        "flagged for review: marked confidential"));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reason", "marked confidential");
                eventLog.audit(AuditActionCatalog.SHARE_LINK_FLAGGED, tenantId, AuditObjectTypes.SHARE_LINK,
                        link.getLinkId(), name, true, me, details);
            }
        }

        List<GuestGrant> grants = new java.util.ArrayList<>();
        if (!documentIds.isEmpty()) {
            grants.addAll(grantRepository.findLiveByDocuments(tenantId, documentIds));
        }
        if (!folderIds.isEmpty()) {
            grants.addAll(grantRepository.findLiveByFolders(tenantId, folderIds));
        }
        for (GuestGrant grant : grants) {
            grant.setRevokedAt(now);
            grant.setRevokedBy(userId);
            grantRepository.save(grant);
            eventLog.record(new ShareEventLog.Subject(tenantId, ShareEventLog.GUEST_GRANT_REVOKED, null,
                    grant.getGuestId(), grant.getTargetType(), grant.targetId(), null, userId, me,
                    "access removed: marked confidential"));
            eventLog.audit(AuditActionCatalog.GUEST_ACCESS_REVOKED, tenantId, AuditObjectTypes.GUEST_ACCOUNT,
                    grant.getGuestId(), null, true, me, Map.of("reason", "marked confidential"));
        }
        return new ConfidentialResult(flagged, revoked, grants.size());
    }

    private String nameOf(UUID tenantId, ShareLink link, Target marked) {
        if (link.targetId().equals(marked.id())) {
            return marked.name();
        }
        return targets.find(tenantId, link.getTargetType(), link.targetId()).map(Target::name).orElse(null);
    }
}
