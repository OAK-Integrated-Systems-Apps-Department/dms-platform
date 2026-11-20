package com.oak.dms.sharing;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderRepository;
import com.oak.dms.sharing.SharingDtos.Eligibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What can be shared, and what is inside a shared folder - the one place the sharing rules about a document or
 * folder live (SEA-FR-007, SEA-NFR-005):
 * <ul>
 *   <li><b>confidential</b> (the item, or a folder it is filed in) - blocked unless a tenant administrator overrides,
 *       with a reason;</li>
 *   <li><b>retention</b> - a document that is under legal hold or no longer {@code active} (archived, past its
 *       retention period) is never shared externally; an administrator can't override that;</li>
 *   <li>a deleted or purged document, or a template, is not shared at all.</li>
 * </ul>
 * Everything inside a shared FOLDER is checked the same way every time it is listed or opened, so a document that
 * becomes confidential, held or archived after the link was made drops out of it at once - and the administrator's
 * override covers only the item they shared, never what is inside it.
 */
@Component
@RequiredArgsConstructor
class ShareTargets {

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;

    /** A document or a folder, resolved inside one tenant. */
    record Target(String type, UUID id, String name, Document document, Folder folder) {
        boolean isDocument() {
            return ShareLink.TYPE_DOCUMENT.equals(type);
        }

        UUID folderIdForTree() {
            return isDocument() ? document.getFolderId() : folder.getFolderId();
        }
    }

    /** The tenant's live folders, read once per request. Not the shared access cache: confidentiality changes must show at once. */
    static final class FolderTree {
        private final Map<UUID, Folder> byId = new HashMap<>();
        private final Map<UUID, List<Folder>> children = new HashMap<>();

        FolderTree(List<Folder> folders) {
            for (Folder folder : folders) {
                byId.put(folder.getFolderId(), folder);
            }
            for (Folder folder : folders) {
                children.computeIfAbsent(folder.getParentFolderId(), k -> new ArrayList<>()).add(folder);
            }
        }

        Optional<Folder> get(UUID folderId) {
            return Optional.ofNullable(folderId == null ? null : byId.get(folderId));
        }

        List<Folder> childrenOf(UUID parentId) {
            return children.getOrDefault(parentId, List.of());
        }

        /** The folder and its parents, nearest first; stops at a missing (deleted) parent. */
        List<Folder> chain(UUID folderId) {
            List<Folder> chain = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            UUID cursor = folderId;
            while (cursor != null && seen.add(cursor)) {
                Folder folder = byId.get(cursor);
                if (folder == null) {
                    break;
                }
                chain.add(folder);
                cursor = folder.getParentFolderId();
            }
            return chain;
        }

        /** True when the folder, or any folder above it, is marked confidential. */
        boolean confidentialChain(UUID folderId) {
            return chain(folderId).stream().anyMatch(Folder::isConfidential);
        }

        /** True when a folder strictly below {@code rootId} on the way down to {@code folderId} (itself included) is confidential. */
        boolean confidentialBelow(UUID folderId, UUID rootId) {
            for (Folder folder : chain(folderId)) {
                if (folder.getFolderId().equals(rootId)) {
                    return false;
                }
                if (folder.isConfidential()) {
                    return true;
                }
            }
            return false;
        }

        /** True when {@code folderId} is {@code rootId} or somewhere beneath it. */
        boolean within(UUID folderId, UUID rootId) {
            return chain(folderId).stream().anyMatch(f -> f.getFolderId().equals(rootId));
        }

        /** The folder and every folder beneath it. */
        Set<UUID> subtreeIds(UUID rootId) {
            Set<UUID> ids = new LinkedHashSet<>();
            if (byId.containsKey(rootId)) {
                collect(rootId, ids);
            }
            return ids;
        }

        private void collect(UUID folderId, Set<UUID> into) {
            if (into.add(folderId)) {
                for (Folder child : childrenOf(folderId)) {
                    collect(child.getFolderId(), into);
                }
            }
        }

        /** The path from {@code rootId} down to {@code folderId}, both included, or empty when it isn't beneath the root. */
        List<Folder> pathFrom(UUID rootId, UUID folderId) {
            List<Folder> chain = chain(folderId);
            int rootAt = -1;
            for (int i = 0; i < chain.size(); i++) {
                if (chain.get(i).getFolderId().equals(rootId)) {
                    rootAt = i;
                    break;
                }
            }
            if (rootAt < 0) {
                return List.of();
            }
            List<Folder> path = new ArrayList<>(chain.subList(0, rootAt + 1));
            Collections.reverse(path);
            return path;
        }
    }

    FolderTree tree(UUID tenantId) {
        return new FolderTree(folderRepository.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(tenantId));
    }

    /** The document or folder, or 404 (also for another tenant's, a deleted one, a purged one). */
    Target resolve(UUID tenantId, String type, UUID id) {
        return find(tenantId, type, id).orElseThrow(() -> new ResourceNotFoundException(
                ShareLink.TYPE_FOLDER.equals(type) ? "Folder" : "Document", id));
    }

    Optional<Target> find(UUID tenantId, String type, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        if (ShareLink.TYPE_DOCUMENT.equals(type)) {
            return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(id, tenantId)
                    .filter(d -> d.getPurgedAt() == null && !d.isTemplate())
                    .map(d -> new Target(type, id, d.getTitle(), d, null));
        }
        if (ShareLink.TYPE_FOLDER.equals(type)) {
            return folderRepository.findByFolderIdAndTenantIdAndDeletedFalse(id, tenantId)
                    .map(f -> new Target(type, id, f.getFolderName(), null, f));
        }
        return Optional.empty();
    }

    static String normaliseType(String type) {
        if (ShareLink.TYPE_DOCUMENT.equalsIgnoreCase(type)) {
            return ShareLink.TYPE_DOCUMENT;
        }
        if (ShareLink.TYPE_FOLDER.equalsIgnoreCase(type)) {
            return ShareLink.TYPE_FOLDER;
        }
        throw SharingException.badRequest("SHARING_TARGET_INVALID", "Choose a document or a folder to share.");
    }

    private static boolean ownConfidential(Target target) {
        return target.isDocument() ? target.document().isConfidential() : target.folder().isConfidential();
    }

    /** Reasons an administrator may override (SEA-FR-007). */
    List<String> confidentialReasons(Target target, FolderTree tree) {
        List<String> reasons = new ArrayList<>();
        boolean own = target.isDocument() ? target.document().isConfidential() : target.folder().isConfidential();
        if (own) {
            reasons.add((target.isDocument() ? "This document" : "This folder") + " is marked confidential.");
        }
        UUID start = target.isDocument() ? target.document().getFolderId() : target.folder().getParentFolderId();
        if (tree.confidentialChain(start)) {
            reasons.add("It is inside a folder that is marked confidential.");
        }
        return reasons;
    }

    /** Reasons nobody can override: retention and legal hold. */
    List<String> retentionReasons(Target target) {
        List<String> reasons = new ArrayList<>();
        if (target.isDocument()) {
            Document d = target.document();
            if (d.isLegalHold()) {
                reasons.add("This document is under legal hold.");
            }
            if (!Document.ACTIVE.equals(d.getLifecycleState())) {
                reasons.add("This document is no longer active (it is archived or past its retention period).");
            }
        }
        return reasons;
    }

    Eligibility eligibility(Target target, FolderTree tree, boolean sharingEnabled, boolean admin) {
        List<String> reasons = new ArrayList<>();
        if (!sharingEnabled) {
            reasons.add("External sharing is turned off for your organisation.");
            return new Eligibility(false, false, reasons, ownConfidential(target));
        }
        List<String> hard = retentionReasons(target);
        List<String> soft = confidentialReasons(target, tree);
        reasons.addAll(hard);
        reasons.addAll(soft);
        boolean canShare = reasons.isEmpty();
        // Only the confidential block can be overridden, and only by a tenant administrator.
        boolean canOverride = admin && hard.isEmpty() && !soft.isEmpty();
        return new Eligibility(canShare, canOverride, reasons, ownConfidential(target));
    }

    /** Retention rules for a document, shared by every way of serving it: not deleted, purged, a template, held or inactive. */
    boolean retentionOk(Document document) {
        return !document.isDeleted() && document.getPurgedAt() == null && !document.isTemplate()
                && !document.isLegalHold() && Document.ACTIVE.equals(document.getLifecycleState());
    }

    /**
     * A document that IS the shared item (a document link, or a guest grant on a document). {@code accepted} = an
     * administrator overrode the confidential block when sharing it (or kept it after review).
     */
    boolean servableRoot(Document document, FolderTree tree, boolean accepted) {
        return retentionOk(document)
                && (accepted || (!document.isConfidential() && !tree.confidentialChain(document.getFolderId())));
    }

    /** A shared FOLDER itself, under the same override rule. */
    boolean servableRoot(Folder folder, FolderTree tree, boolean accepted) {
        return !folder.isDeleted() && (accepted || !tree.confidentialChain(folder.getFolderId()));
    }

    /**
     * A document inside a shared folder: never confidential itself, never inside a confidential sub-folder. The
     * override covers the folder that was shared, not what is below it (so confidentiality AT or ABOVE the shared
     * root is accepted, and only the part beneath it is checked).
     */
    boolean servableBelow(Document document, FolderTree tree, UUID rootFolderId) {
        return retentionOk(document) && !document.isConfidential()
                && !tree.confidentialBelow(document.getFolderId(), rootFolderId);
    }

    /** A sub-folder inside a shared folder - see {@link #servableBelow(Document, FolderTree, UUID)}. */
    boolean servableBelow(Folder folder, FolderTree tree, UUID rootFolderId) {
        return !folder.isDeleted() && !tree.confidentialBelow(folder.getFolderId(), rootFolderId);
    }
}
