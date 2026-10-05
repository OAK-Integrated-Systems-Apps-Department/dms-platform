package com.oak.dms.folder;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.AccessPrincipals.Grant;
import com.oak.dms.folder.dto.FolderDtos.DocumentAccessResponse;
import com.oak.dms.folder.dto.FolderDtos.FolderAccessResponse;
import com.oak.dms.folder.dto.FolderDtos.UpdateFolderAccessRequest;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Document access control (V54), on top of folder access (V53). A document
 * either follows its folder ({@code inherit} - a top-level one is open to
 * everyone) or is {@code restricted} to its own list of users and roles,
 * whatever its folder says - like SharePoint's unique permissions. Tenant
 * admins always have access.
 * <p>
 * A document the caller can't open answers 404, exactly as if it didn't
 * exist; one they can open but not change answers 403. Needs {@link TenantContext}.
 */
@Service
@RequiredArgsConstructor
public class DocumentAccessService {

    private final FolderAccessService folderAccess;
    private final DocumentAccessRepository accessRepository;
    private final DocumentRepository documentRepository;
    private final AccessPrincipals principals;
    private final AuditService auditService;
    private final AccessCache cache;

    /**
     * The caller's access to folders and to restricted documents, worked out once per request.
     *
     * @param granted       best level the caller's own entries give, per restricted document
     * @param anyRestricted whether the tenant has any restricted document at all
     */
    public record DocumentAccessView(FolderAccessView folders, Map<UUID, FolderAccessLevel> granted, boolean admin,
                                     boolean anyRestricted) {

        public FolderAccessLevel level(Document document) {
            FolderAccessLevel level = admin ? FolderAccessLevel.manage
                    : document.getAccessMode() == FolderAccessMode.restricted
                    ? granted.getOrDefault(document.getDocumentId(), FolderAccessLevel.none)
                    : folders.level(document.getFolderId());
            // A template is only ever opened, downloaded or copied here - it's changed through
            // DocumentTemplateService, with the document-type permission, never as a document.
            return document.isTemplate() && level.atLeast(FolderAccessLevel.view) ? FolderAccessLevel.view : level;
        }

        public boolean canSee(Document document) {
            return level(document).atLeast(FolderAccessLevel.view);
        }

        /** Restricted documents the caller is on the list of - never empty, for an IN clause. */
        public Set<UUID> grantedIds() {
            return granted.isEmpty() ? Set.of(AccessPrincipals.NONE) : granted.keySet();
        }

        /** No filtering needed: an admin, or nothing restricted anywhere. */
        public boolean seesEverything() {
            return admin || (!anyRestricted && folders.hiddenFolderIds().isEmpty());
        }
    }

    @Transactional(readOnly = true)
    public DocumentAccessView forCaller() {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        FolderAccessView folders = folderAccess.forCaller();
        boolean admin = folderAccess.callerIsAdmin();
        // Deleted ones too: a restricted document in the recycle bin must stay open to the people on its
        // list, so they can restore it - even when no live document is restricted.
        boolean anyRestricted = cache.anyRestrictedDocuments(tenantId, () ->
                documentRepository.existsRestrictedNotPurged(tenantId));
        Map<UUID, FolderAccessLevel> granted = Map.of();
        if (!admin && anyRestricted) {
            granted = cache.documentGrants(tenantId, userId, () -> {
                Map<UUID, FolderAccessLevel> best = new HashMap<>();
                for (DocumentAccessEntry entry : accessRepository.findForPrincipals(tenantId, userId,
                        principals.callerRoleIds())) {
                    best.merge(entry.getDocumentId(), entry.getAccessLevel(), (a, b) -> a.atLeast(b) ? a : b);
                }
                return best;
            });
        }
        return new DocumentAccessView(folders, granted, admin, anyRestricted);
    }

    /** Restricted documents the caller isn't on the list of - left out of exports. */
    @Transactional(readOnly = true)
    public Set<UUID> hiddenRestrictedIds(DocumentAccessView view) {
        if (view.admin() || !view.anyRestricted()) {
            return Set.of();
        }
        Set<UUID> hidden = new HashSet<>(documentRepository.findRestrictedIds(TenantContext.getTenantId()));
        hidden.removeAll(view.granted().keySet());
        return hidden;
    }

    /** Per folder, how many of its documents are restricted away from the caller - so folder counts don't leak them. */
    @Transactional(readOnly = true)
    public Map<UUID, Long> hiddenCountsPerFolder(DocumentAccessView view) {
        Map<UUID, Long> counts = new HashMap<>();
        if (view.admin() || !view.anyRestricted()) {
            return counts;
        }
        for (Object[] row : documentRepository.findRestrictedIdsWithFolder(TenantContext.getTenantId())) {
            UUID documentId = (UUID) row[0];
            UUID folderId = (UUID) row[1];
            if (folderId != null && !view.granted().containsKey(documentId)) {
                counts.merge(folderId, 1L, Long::sum);
            }
        }
        return counts;
    }

    /** The document if the caller has at least {@code needed} on it: 404 when they can't open it, 403 when they can't do this. */
    public Document require(DocumentAccessView view, Document document, FolderAccessLevel needed) {
        FolderAccessLevel level = view.level(document);
        if (!level.atLeast(FolderAccessLevel.view)) {
            throw new ResourceNotFoundException("Document", document.getDocumentId());
        }
        if (!level.atLeast(needed)) {
            String name = "\"" + document.getTitle() + "\"";
            throw new FolderException(HttpStatus.FORBIDDEN, "DOCUMENT_ACCESS_DENIED", needed == FolderAccessLevel.manage
                    ? "Only people who manage " + name + " can do that."
                    : "You can open " + name + " but not change it.");
        }
        return document;
    }

    @Transactional(readOnly = true)
    public DocumentAccessResponse getAccess(UUID documentId) {
        DocumentAccessView view = forCaller();
        Document document = require(view, find(documentId), FolderAccessLevel.view);
        FolderAccessLevel mine = view.level(document);
        if (document.getAccessMode() == FolderAccessMode.restricted) {
            return new DocumentAccessResponse(documentId, FolderAccessMode.restricted.name(),
                    FolderAccessMode.restricted.name(), document.getFolderId(), null, null,
                    principals.describe(grants(accessRepository.findByTenantIdAndDocumentIdOrderByCreatedAtAsc(
                            document.getTenantId(), documentId))),
                    mine.name(), folderAccess.canManage(mine));
        }
        if (document.getFolderId() == null) {
            return new DocumentAccessResponse(documentId, FolderAccessMode.inherit.name(),
                    FolderAccessMode.everyone.name(), null, null, null, List.of(), mine.name(),
                    folderAccess.canManage(mine));
        }
        // Follows its folder - show what the folder says.
        FolderAccessResponse folder = folderAccess.getAccess(document.getFolderId());
        UUID ruleId = folder.inheritedFromFolderId() != null ? folder.inheritedFromFolderId()
                : !FolderAccessMode.inherit.name().equals(folder.mode()) ? document.getFolderId() : null;
        String ruleName = folder.inheritedFromName() != null ? folder.inheritedFromName()
                : ruleId != null ? view.folders().folder(ruleId).map(Folder::getFolderName).orElse(null) : null;
        return new DocumentAccessResponse(documentId, FolderAccessMode.inherit.name(), folder.effectiveMode(),
                document.getFolderId(), ruleId, ruleName, folder.entries(), mine.name(), folderAccess.canManage(mine));
    }

    /**
     * Gives the document its own list ({@code restricted}) or makes it follow
     * its folder again ({@code inherit}). Needs manage on the document (the
     * controller checks the permission code); restricting it keeps the
     * person doing it as a manager.
     */
    @Transactional
    public DocumentAccessResponse updateAccess(UUID documentId, UpdateFolderAccessRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = require(forCaller(), find(documentId), FolderAccessLevel.manage);
        FolderAccessMode mode = AccessPrincipals.parseEnum(FolderAccessMode.class, request.mode(), "access mode");
        if (mode == FolderAccessMode.everyone) {
            throw FolderException.badRequest("FOLDER_ACCESS_INVALID",
                    "A document follows its folder or has its own list - open its folder to everyone instead.");
        }

        Map<String, Grant> wanted = new LinkedHashMap<>();
        if (mode == FolderAccessMode.restricted) {
            wanted = principals.parse(request.entries());
            if (!folderAccess.callerIsAdmin()) {
                principals.keepCallerAsManager(wanted);
            }
        }

        Map<String, Object> previous = AccessPrincipals.snapshot(document.getAccessMode(),
                grants(accessRepository.findByTenantIdAndDocumentIdOrderByCreatedAtAsc(tenantId, documentId)));
        document.setAccessMode(mode);
        document.setUpdatedAt(OffsetDateTime.now());
        document.setUpdatedBy(TenantContext.getUserId());
        documentRepository.save(document);
        accessRepository.deleteByDocument(tenantId, documentId);
        accessRepository.saveAll(wanted.values().stream().map(g -> DocumentAccessEntry.builder()
                .tenantId(tenantId)
                .documentId(documentId)
                .principalType(g.type())
                .principalId(g.principalId())
                .accessLevel(g.level())
                .createdBy(TenantContext.getUserId())
                .build()).toList());

        auditService.event(AuditActionCatalog.DOCUMENT_ACCESS_CHANGED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previous)
                .newValue(AccessPrincipals.snapshot(mode, wanted.values()))
                .log();
        return getAccess(documentId);
    }

    private Document find(UUID documentId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    private static List<Grant> grants(List<DocumentAccessEntry> entries) {
        return entries.stream().map(e -> new Grant(e.getPrincipalType(), e.getPrincipalId(), e.getAccessLevel()))
                .toList();
    }
}
