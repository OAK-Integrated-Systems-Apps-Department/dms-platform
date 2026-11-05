package com.oak.dms.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the document browser: the document plus what's shown about its
 * current (or latest) version. Never carries storage locations or keys.
 */
public record DocumentListItem(
        UUID documentId,
        String title,
        UUID documentTypeId,
        String documentTypeName,
        UUID folderId,
        String fileName,
        Long sizeBytes,
        String mimeType,
        Integer versionNumber,
        UUID ownerUserId,
        OffsetDateTime createdAt,
        /** Has its own access list (V54) instead of following its folder. */
        boolean restricted,
        /** What the caller may do with it: view, edit or manage. */
        String myAccess,
        /** The owner's department (V123); null when the owner had none. */
        UUID ownerDepartmentId,
        /** assigned, or holding while it waits for a new owner (V123). */
        String ownershipState,
        /**
         * Why it can't be edited right now, for the list's lock icon (V127): {@code checked_out_by_me},
         * {@code checked_out} (someone else), {@code legal_hold}, or null when it is free. Who holds it
         * is one click away on the document page.
         */
        String lockState,
        /**
         * active, inactive or archived (V130). Normal listings only ever hold active ones; the Archive view holds the
         * other two. Shown as the status badge on every document (CRM-FR-001).
         */
        String lifecycleState,
        /** When the retention rule will archive it (null when no rule covers it or it is already archived). */
        OffsetDateTime archiveDueAt
) {

    /** Every row built before the lifecycle existed: active. */
    public DocumentListItem(UUID documentId, String title, UUID documentTypeId, String documentTypeName, UUID folderId,
                            String fileName, Long sizeBytes, String mimeType, Integer versionNumber, UUID ownerUserId,
                            OffsetDateTime createdAt, boolean restricted, String myAccess, UUID ownerDepartmentId,
                            String ownershipState, String lockState) {
        this(documentId, title, documentTypeId, documentTypeName, folderId, fileName, sizeBytes, mimeType, versionNumber,
                ownerUserId, createdAt, restricted, myAccess, ownerDepartmentId, ownershipState, lockState, "active", null);
    }

    /** Every row built before the lock icon existed: free. */
    public DocumentListItem(UUID documentId, String title, UUID documentTypeId, String documentTypeName, UUID folderId,
                            String fileName, Long sizeBytes, String mimeType, Integer versionNumber, UUID ownerUserId,
                            OffsetDateTime createdAt, boolean restricted, String myAccess, UUID ownerDepartmentId,
                            String ownershipState) {
        this(documentId, title, documentTypeId, documentTypeName, folderId, fileName, sizeBytes, mimeType, versionNumber,
                ownerUserId, createdAt, restricted, myAccess, ownerDepartmentId, ownershipState, (String) null);
    }

    public DocumentListItem(UUID documentId, String title, UUID documentTypeId, String documentTypeName, UUID folderId,
                            String fileName, Long sizeBytes, String mimeType, Integer versionNumber, UUID ownerUserId,
                            OffsetDateTime createdAt, boolean restricted, String myAccess) {
        this(documentId, title, documentTypeId, documentTypeName, folderId, fileName, sizeBytes, mimeType, versionNumber,
                ownerUserId, createdAt, restricted, myAccess, null, "assigned");
    }
}
