package com.oak.dms.folder;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.AccessPrincipals.Grant;
import com.oak.dms.folder.dto.FolderDtos.AccessPrincipal;
import com.oak.dms.folder.dto.FolderDtos.FolderAccessEntryResponse;
import com.oak.dms.folder.dto.FolderDtos.FolderAccessResponse;
import com.oak.dms.folder.dto.FolderDtos.UpdateFolderAccessRequest;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Folder access control (Module DSC, V53): who can get into which folder,
 * and the checks every folder and document operation runs through. See
 * {@link FolderAccessView} for how a folder's access is worked out.
 * <p>
 * A folder the caller can't see answers 404 everywhere, exactly as if it
 * didn't exist - its name and contents never leak. One they can see but
 * not change answers 403. Needs {@link TenantContext}.
 */
@Service
@RequiredArgsConstructor
public class FolderAccessService {

    private final FolderRepository folderRepository;
    private final FolderAccessRepository accessRepository;
    private final AccessPrincipals principals;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    /** The caller's access to every folder of their tenant. */
    @Transactional(readOnly = true)
    public FolderAccessView forCaller() {
        UUID tenantId = TenantContext.getTenantId();
        List<Folder> folders = folderRepository.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(tenantId);
        boolean admin = authorizationService.isTenantAdmin();
        Map<UUID, FolderAccessLevel> granted = new HashMap<>();
        // Only look the caller's entries up when something is actually restricted.
        if (!admin && folders.stream().anyMatch(f -> f.getAccessMode() == FolderAccessMode.restricted)) {
            for (FolderAccessEntry entry : accessRepository.findForPrincipals(tenantId, TenantContext.getUserId(),
                    principals.callerRoleIds())) {
                granted.merge(entry.getFolderId(), entry.getAccessLevel(), (a, b) -> a.atLeast(b) ? a : b);
            }
        }
        return new FolderAccessView(folders, granted, admin);
    }

    /** Whether the caller is a tenant admin - full access to every folder and document. */
    public boolean callerIsAdmin() {
        return authorizationService.isTenantAdmin();
    }

    /**
     * A live folder the caller has at least {@code needed} in: 404 when they
     * can't see it (or it doesn't exist), 403 when they can see it but not do this.
     */
    @Transactional(readOnly = true)
    public Folder require(UUID folderId, FolderAccessLevel needed) {
        return require(forCaller(), folderId, needed);
    }

    public Folder require(FolderAccessView view, UUID folderId, FolderAccessLevel needed) {
        Folder folder = view.folder(folderId)
                .filter(f -> view.canSee(f.getFolderId()))
                .orElseThrow(() -> new ResourceNotFoundException("Folder", folderId));
        if (!view.level(folderId).atLeast(needed)) {
            throw denied(folder, needed);
        }
        return folder;
    }

    @Transactional(readOnly = true)
    public FolderAccessResponse getAccess(UUID folderId) {
        FolderAccessView view = forCaller();
        Folder folder = require(view, folderId, FolderAccessLevel.view);
        Folder rule = view.governingFolder(folderId).orElse(null);
        boolean inherited = rule == null || !rule.getFolderId().equals(folderId);
        List<FolderAccessEntryResponse> entries = rule != null && rule.getAccessMode() == FolderAccessMode.restricted
                ? principals.describe(grants(accessRepository.findByTenantIdAndFolderIdOrderByCreatedAtAsc(
                folder.getTenantId(), rule.getFolderId())))
                : List.of();
        FolderAccessLevel mine = view.level(folderId);
        return new FolderAccessResponse(folderId, folder.getAccessMode().name(),
                view.restricted(folderId) ? FolderAccessMode.restricted.name() : FolderAccessMode.everyone.name(),
                inherited && rule != null ? rule.getFolderId() : null,
                inherited && rule != null ? rule.getFolderName() : null,
                entries, mine.name(), canManage(mine));
    }

    /**
     * Replaces the folder's access setting. Needs manage in the folder (the
     * controller checks the permission code). Making a folder restricted
     * always keeps the person doing it as a manager - nobody but an admin can
     * lock themselves out by accident.
     */
    @Transactional
    public FolderAccessResponse updateAccess(UUID folderId, UpdateFolderAccessRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        Folder folder = require(forCaller(), folderId, FolderAccessLevel.manage);
        FolderAccessMode mode = AccessPrincipals.parseEnum(FolderAccessMode.class, request.mode(), "access mode");

        Map<String, Grant> wanted = new LinkedHashMap<>();
        if (mode == FolderAccessMode.restricted) {
            wanted = principals.parse(request.entries());
            if (!authorizationService.isTenantAdmin()) {
                principals.keepCallerAsManager(wanted);
            }
        }

        Map<String, Object> previous = AccessPrincipals.snapshot(folder.getAccessMode(),
                grants(accessRepository.findByTenantIdAndFolderIdOrderByCreatedAtAsc(tenantId, folderId)));
        folder.setAccessMode(mode);
        folder.setUpdatedAt(OffsetDateTime.now());
        folder.setUpdatedBy(TenantContext.getUserId());
        folderRepository.save(folder);
        accessRepository.deleteByFolder(tenantId, folderId);
        accessRepository.saveAll(wanted.values().stream().map(g -> FolderAccessEntry.builder()
                .tenantId(tenantId)
                .folderId(folderId)
                .principalType(g.type())
                .principalId(g.principalId())
                .accessLevel(g.level())
                .createdBy(TenantContext.getUserId())
                .build()).toList());

        auditService.event(AuditActionCatalog.FOLDER_ACCESS_CHANGED)
                .objectType(AuditObjectTypes.FOLDER)
                .objectId(folderId)
                .resourceName(folder.getFolderName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previous)
                .newValue(AccessPrincipals.snapshot(mode, wanted.values()))
                .log();
        return getAccess(folderId);
    }

    /** Users and roles matching {@code q}, for the access picker. */
    @Transactional(readOnly = true)
    public List<AccessPrincipal> searchPrincipals(String q) {
        return principals.search(q);
    }

    /** Manage level plus the permission code - who may change a folder's or document's access. */
    boolean canManage(FolderAccessLevel level) {
        return level.atLeast(FolderAccessLevel.manage)
                && authorizationService.hasPermission(PermissionCodes.DSC_FOLDER_ACCESS_ASSIGN);
    }

    private static FolderException denied(Folder folder, FolderAccessLevel needed) {
        String name = "\"" + folder.getFolderName() + "\"";
        return FolderException.forbidden("FOLDER_ACCESS_DENIED", needed == FolderAccessLevel.manage
                ? "Only people who manage " + name + " can do that."
                : "You can view " + name + " but not change what's in it.");
    }

    private static List<Grant> grants(List<FolderAccessEntry> entries) {
        return entries.stream().map(e -> new Grant(e.getPrincipalType(), e.getPrincipalId(), e.getAccessLevel()))
                .toList();
    }
}
