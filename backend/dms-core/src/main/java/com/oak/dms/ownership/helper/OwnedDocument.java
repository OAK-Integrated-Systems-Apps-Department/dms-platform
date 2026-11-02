package com.oak.dms.ownership.helper;

import com.oak.dms.document.Document;
import com.oak.dms.folder.FolderAccessMode;

import java.util.UUID;

/**
 * A locked document row as the transfer code sees it - read with SQL, never as a JPA entity, so no stale
 * persistence-context copy can be written back after the set-based UPDATE.
 */
public record OwnedDocument(UUID documentId, UUID tenantId, String title, UUID ownerUserId, UUID ownerDepartmentId,
                            String ownershipState, UUID folderId, String accessMode, boolean template, boolean deleted) {

    /** A detached Document carrying just what {@code DocumentAccessView.level} reads. Never saved. */
    public Document accessStub() {
        return Document.builder()
                .documentId(documentId)
                .tenantId(tenantId)
                .title(title)
                .ownerUserId(ownerUserId)
                .folderId(folderId)
                .template(template)
                .deleted(deleted)
                .accessMode(FolderAccessMode.valueOf(accessMode))
                .build();
    }

    public boolean restricted() {
        return FolderAccessMode.restricted.name().equals(accessMode);
    }
}
