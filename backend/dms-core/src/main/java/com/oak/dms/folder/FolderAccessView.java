package com.oak.dms.folder;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One caller's access to every folder of their tenant, worked out once per
 * request by {@link FolderAccessService#forCaller()}.
 * <p>
 * A folder's access is decided by its <em>governing</em> folder: itself if it
 * says {@code everyone} or {@code restricted}, otherwise the nearest ancestor
 * that does. No such ancestor (or one that says {@code everyone}) means open
 * to everyone - the caller gets {@link FolderAccessLevel#manage}, i.e. only
 * their permission codes limit them. A {@code restricted} governing folder
 * gives the best level any of the caller's entries on it grants, or
 * {@link FolderAccessLevel#none}. Tenant admins get {@code manage} everywhere,
 * so no folder can ever be locked away from the organisation.
 * <p>
 * Documents at the top level (no folder) are open to everyone.
 */
public final class FolderAccessView {

    private final Map<UUID, Folder> folders;
    private final Map<UUID, FolderAccessLevel> levels = new HashMap<>();
    /** folderId -> governing restricted/everyone folder; absent = open by default. */
    private final Map<UUID, Folder> governing = new HashMap<>();

    /**
     * @param granted best level the caller's own entries give, per restricted folder id
     * @param admin   tenant admin - full access everywhere
     */
    public FolderAccessView(Collection<Folder> folders, Map<UUID, FolderAccessLevel> granted, boolean admin) {
        this.folders = new HashMap<>();
        for (Folder folder : folders) {
            this.folders.put(folder.getFolderId(), folder);
        }
        for (Folder folder : folders) {
            Folder rule = governingOf(folder);
            if (rule != null) {
                governing.put(folder.getFolderId(), rule);
            }
            FolderAccessLevel level;
            if (admin || rule == null || rule.getAccessMode() == FolderAccessMode.everyone) {
                level = FolderAccessLevel.manage;
            } else {
                level = granted.getOrDefault(rule.getFolderId(), FolderAccessLevel.none);
            }
            levels.put(folder.getFolderId(), level);
        }
    }

    /** The caller's level in the folder; the top level (null) is open to everyone; an unknown folder is none. */
    public FolderAccessLevel level(UUID folderId) {
        if (folderId == null) {
            return FolderAccessLevel.manage;
        }
        return levels.getOrDefault(folderId, FolderAccessLevel.none);
    }

    public boolean canSee(UUID folderId) {
        return level(folderId).atLeast(FolderAccessLevel.view);
    }

    /** Whether the folder is restricted (itself or through an ancestor), whatever the caller's own level. */
    public boolean restricted(UUID folderId) {
        Folder rule = governing.get(folderId);
        return rule != null && rule.getAccessMode() == FolderAccessMode.restricted;
    }

    /** The folder whose setting applies to this one (itself included); empty = open by default. */
    public Optional<Folder> governingFolder(UUID folderId) {
        return Optional.ofNullable(governing.get(folderId));
    }

    public Optional<Folder> folder(UUID folderId) {
        return Optional.ofNullable(folders.get(folderId));
    }

    /** Live folders the caller can't see - excluded from document lists and exports. */
    public Set<UUID> hiddenFolderIds() {
        Set<UUID> hidden = new HashSet<>();
        levels.forEach((id, level) -> {
            if (!level.atLeast(FolderAccessLevel.view)) {
                hidden.add(id);
            }
        });
        return hidden;
    }

    private Folder governingOf(Folder folder) {
        Set<UUID> seen = new HashSet<>();
        Folder current = folder;
        while (current != null && seen.add(current.getFolderId()) && seen.size() <= FolderService.MAX_DEPTH + 1) {
            if (current.getAccessMode() != null && current.getAccessMode() != FolderAccessMode.inherit) {
                return current;
            }
            current = current.getParentFolderId() == null ? null : folders.get(current.getParentFolderId());
        }
        return null;
    }
}
