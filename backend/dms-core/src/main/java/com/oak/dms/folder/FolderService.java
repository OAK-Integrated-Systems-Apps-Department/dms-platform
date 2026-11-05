package com.oak.dms.folder;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.dto.FolderDtos.FolderPathItem;
import com.oak.dms.folder.dto.FolderDtos.FolderResponse;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The tenant's shared folder tree (DSC-FR-002/003/009/010). Every rule the
 * tree needs is enforced here, with the database as a backstop:
 * <ul>
 *   <li>names are unique among a folder's children, ignoring case
 *       (uq_folders_live_sibling_name);</li>
 *   <li>a folder can't be moved into itself or anything below it;</li>
 *   <li>only an empty folder - no subfolders, no documents, no upload still
 *       being scanned into it - can be deleted, so nothing is ever orphaned
 *       (DSC-NFR-006).</li>
 * </ul>
 * Folder access (V53, {@link FolderAccessService}) applies throughout: the
 * list holds only folders the caller can see, a hidden folder answers 404,
 * creating and renaming need edit, moving and deleting need manage.
 * Needs {@link TenantContext}.
 */
@Service
@RequiredArgsConstructor
public class FolderService {

    static final int MAX_DEPTH = 20;
    /** Characters that break paths or look like paths. */
    private static final Pattern INVALID_NAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private final FolderRepository repository;
    private final AuditService auditService;
    private final FolderAccessService access;
    private final DocumentAccessService documentAccess;
    private final AccessPrincipals principals;

    @Transactional(readOnly = true)
    public List<FolderResponse> listAll() {
        UUID tenantId = TenantContext.getTenantId();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : repository.countDocumentsPerFolder(tenantId)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        DocumentAccessService.DocumentAccessView documents = documentAccess.forCaller();
        FolderAccessView view = documents.folders();
        // Restricted documents the caller isn't on the list of don't count (V54).
        documentAccess.hiddenCountsPerFolder(documents).forEach((id, hidden) -> counts.merge(id, -hidden, Long::sum));
        return repository.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(tenantId).stream()
                .filter(f -> view.canSee(f.getFolderId()))
                .map(f -> toResponse(f, counts.getOrDefault(f.getFolderId(), 0L), view))
                .toList();
    }

    /** Restricted folders shared with the caller by name or role (not ones they created) - "Shared with me". */
    @Transactional(readOnly = true)
    public List<FolderResponse> sharedWithMe() {
        UUID tenantId = TenantContext.getTenantId();
        DocumentAccessService.DocumentAccessView documents = documentAccess.forCaller();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : repository.countDocumentsPerFolder(tenantId)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        documentAccess.hiddenCountsPerFolder(documents).forEach((id, hidden) -> counts.merge(id, -hidden, Long::sum));
        return repository.findSharedWith(tenantId, TenantContext.getUserId(), principals.callerRoleIds()).stream()
                .map(f -> toResponse(f, counts.getOrDefault(f.getFolderId(), 0L), documents.folders()))
                .toList();
    }

    /** The folder's path from the top level down to it (DSC-FR-010). */
    @Transactional(readOnly = true)
    public List<FolderPathItem> path(UUID folderId) {
        FolderAccessView view = access.forCaller();
        access.require(view, folderId, FolderAccessLevel.view);
        List<FolderPathItem> path = new ArrayList<>();
        for (FolderPathItem item : treePath(folderId)) {
            if (view.canSee(item.folderId())) {
                path.add(item);
            }
        }
        return path;
    }

    /** Every folder from the top level down to this one, whatever the caller can see - for depth checks. */
    private List<FolderPathItem> treePath(UUID folderId) {
        UUID tenantId = TenantContext.getTenantId();
        List<FolderPathItem> path = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = folderId;
        while (current != null && seen.add(current) && path.size() <= MAX_DEPTH) {
            Folder folder = own(current, tenantId);
            path.add(new FolderPathItem(folder.getFolderId(), folder.getFolderName()));
            current = folder.getParentFolderId();
        }
        Collections.reverse(path);
        return path;
    }

    /** A live folder the caller can see, or 404 - for listing what's in it. */
    @Transactional(readOnly = true)
    public Folder require(UUID folderId) {
        return access.require(folderId, FolderAccessLevel.view);
    }

    /** A live folder of the tenant, or 404, with no access check - only for background jobs checked when requested. */
    @Transactional(readOnly = true)
    public Folder requireLive(UUID folderId) {
        return own(folderId, TenantContext.getTenantId());
    }

    /** A live folder the caller can put things into (edit), else 404/403 - for uploads, imports and moves. */
    @Transactional(readOnly = true)
    public Folder requireEditable(UUID folderId) {
        return access.require(folderId, FolderAccessLevel.edit);
    }

    @Transactional
    public FolderResponse create(String name, UUID parentFolderId) {
        UUID tenantId = TenantContext.getTenantId();
        String clean = validName(name);
        if (parentFolderId != null) {
            access.require(parentFolderId, FolderAccessLevel.edit);
            if (treePath(parentFolderId).size() >= MAX_DEPTH) {
                throw FolderException.badRequest("FOLDER_TOO_DEEP", "Folders can be nested at most " + MAX_DEPTH + " deep.");
            }
        }
        requireFreeName(tenantId, parentFolderId, clean, null);
        Folder saved = save(Folder.builder()
                .tenantId(tenantId)
                .parentFolderId(parentFolderId)
                .folderName(clean)
                .createdBy(TenantContext.getUserId())
                .build());
        audit(AuditActionCatalog.FOLDER_CREATED, saved, null);
        return toResponse(saved, 0, access.forCaller());
    }

    @Transactional
    public FolderResponse rename(UUID folderId, String name) {
        UUID tenantId = TenantContext.getTenantId();
        access.require(folderId, FolderAccessLevel.edit);
        Folder folder = own(folderId, tenantId);
        String clean = validName(name);
        requireFreeName(tenantId, folder.getParentFolderId(), clean, folder.getFolderId());
        Map<String, Object> before = Map.of("name", folder.getFolderName());
        folder.setFolderName(clean);
        touch(folder);
        Folder saved = save(folder);
        audit(AuditActionCatalog.FOLDER_RENAMED, saved, before);
        return toResponse(saved, repository.countActiveDocuments(tenantId, folderId), access.forCaller());
    }

    @Transactional
    public FolderResponse move(UUID folderId, UUID newParentId) {
        UUID tenantId = TenantContext.getTenantId();
        FolderAccessView view = access.forCaller();
        access.require(view, folderId, FolderAccessLevel.manage);
        Folder folder = own(folderId, tenantId);
        if (newParentId != null) {
            access.require(view, newParentId, FolderAccessLevel.edit);
            if (repository.findSubtreeIds(tenantId, folderId).contains(newParentId)) {
                throw FolderException.badRequest("FOLDER_MOVE_INTO_ITSELF",
                        "A folder can't be moved into itself or one of its own subfolders.");
            }
            if (treePath(newParentId).size() + depthBelow(tenantId, folderId) >= MAX_DEPTH) {
                throw FolderException.badRequest("FOLDER_TOO_DEEP", "Folders can be nested at most " + MAX_DEPTH + " deep.");
            }
        }
        requireFreeName(tenantId, newParentId, folder.getFolderName(), folder.getFolderId());
        Map<String, Object> before = new HashMap<>();
        before.put("parentFolderId", folder.getParentFolderId());
        folder.setParentFolderId(newParentId);
        touch(folder);
        Folder saved = save(folder);
        audit(AuditActionCatalog.FOLDER_MOVED, saved, before);
        return toResponse(saved, repository.countActiveDocuments(tenantId, folderId), access.forCaller());
    }

    @Transactional
    public void delete(UUID folderId) {
        UUID tenantId = TenantContext.getTenantId();
        access.require(folderId, FolderAccessLevel.manage);
        Folder folder = own(folderId, tenantId);
        if (repository.existsByTenantIdAndParentFolderIdAndDeletedFalse(tenantId, folderId)) {
            throw FolderException.conflict("FOLDER_NOT_EMPTY", "\"" + folder.getFolderName()
                    + "\" has subfolders. Move or delete them first.");
        }
        // Every live document counts here - including inactive and archived ones, which the folder's listing hides but which
        // would be orphaned if the folder went. So the message says so when that is all that is left.
        long documents = repository.countDocuments(tenantId, folderId);
        if (documents > 0) {
            boolean onlyHidden = repository.countActiveDocuments(tenantId, folderId) == 0;
            throw FolderException.conflict("FOLDER_NOT_EMPTY", "\"" + folder.getFolderName() + "\" holds " + documents
                    + (onlyHidden ? " inactive or archived " : " ") + (documents == 1 ? "document" : "documents")
                    + (onlyHidden ? " (hidden from the folder's list - find them in Inactive & Archive)" : "")
                    + ". Move them to another folder first.");
        }
        if (repository.countPendingUploads(tenantId, folderId) > 0) {
            throw FolderException.conflict("FOLDER_NOT_EMPTY", "Files are still being uploaded into \""
                    + folder.getFolderName() + "\". Try again once they're done.");
        }
        folder.setDeleted(true);
        folder.setDeletedAt(OffsetDateTime.now());
        touch(folder);
        repository.save(folder);
        audit(AuditActionCatalog.FOLDER_DELETED, folder, null);
    }

    // ------------------------------------------------------------ helpers

    private Folder own(UUID folderId, UUID tenantId) {
        return repository.findByFolderIdAndTenantIdAndDeletedFalse(folderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Folder", folderId));
    }

    static String validName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw FolderException.badRequest("FOLDER_NAME_INVALID", "Give the folder a name.");
        }
        if (clean.length() > 255) {
            throw FolderException.badRequest("FOLDER_NAME_INVALID", "A folder name can be at most 255 characters.");
        }
        if (INVALID_NAME.matcher(clean).find() || clean.equals(".") || clean.equals("..")) {
            throw FolderException.badRequest("FOLDER_NAME_INVALID",
                    "A folder name can't contain \\ / : * ? \" < > | or be . or ..");
        }
        return clean;
    }

    private void requireFreeName(UUID tenantId, UUID parentId, String name, UUID self) {
        List<Folder> siblings = parentId == null
                ? repository.findByTenantIdAndParentFolderIdIsNullAndDeletedFalse(tenantId)
                : repository.findByTenantIdAndParentFolderIdAndDeletedFalse(tenantId, parentId);
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Folder sibling : siblings) {
            if (!sibling.getFolderId().equals(self) && sibling.getFolderName().toLowerCase(Locale.ROOT).equals(wanted)) {
                throw FolderException.conflict("FOLDER_NAME_TAKEN", "There's already a folder called \""
                        + sibling.getFolderName() + "\" here.");
            }
        }
    }

    /** How many levels the folder's subtree adds below it (0 for a leaf). */
    private int depthBelow(UUID tenantId, UUID folderId) {
        int depth = 0;
        List<UUID> level = List.of(folderId);
        while (!level.isEmpty() && depth <= MAX_DEPTH) {
            List<UUID> next = new ArrayList<>();
            for (UUID id : level) {
                repository.findByTenantIdAndParentFolderIdAndDeletedFalse(tenantId, id)
                        .forEach(child -> next.add(child.getFolderId()));
            }
            if (next.isEmpty()) {
                break;
            }
            depth++;
            level = next;
        }
        return depth;
    }

    /** Saves, turning a lost race on the sibling-name index into the same 409 as the check. */
    private Folder save(Folder folder) {
        try {
            return repository.saveAndFlush(folder);
        } catch (DataIntegrityViolationException e) {
            throw FolderException.conflict("FOLDER_NAME_TAKEN", "There's already a folder called \""
                    + folder.getFolderName() + "\" here.");
        }
    }

    private static void touch(Folder folder) {
        folder.setUpdatedAt(OffsetDateTime.now());
        folder.setUpdatedBy(TenantContext.getUserId());
    }

    private void audit(String action, Folder folder, Map<String, Object> before) {
        Map<String, Object> after = new HashMap<>();
        after.put("name", folder.getFolderName());
        after.put("parentFolderId", folder.getParentFolderId());
        var event = auditService.event(action)
                .objectType(AuditObjectTypes.FOLDER)
                .objectId(folder.getFolderId())
                .resourceName(folder.getFolderName())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(after);
        if (before != null) {
            event.previousValue(before);
        }
        event.log();
    }

    private static FolderResponse toResponse(Folder f, long documentCount, FolderAccessView view) {
        return new FolderResponse(f.getFolderId(), f.getParentFolderId(), f.getFolderName(), documentCount,
                f.getCreatedAt(), f.getUpdatedAt(), f.getAccessMode().name(), view.restricted(f.getFolderId()),
                view.level(f.getFolderId()).name());
    }
}
