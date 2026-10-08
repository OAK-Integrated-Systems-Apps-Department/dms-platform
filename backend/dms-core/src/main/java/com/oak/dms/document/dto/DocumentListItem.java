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
        String myAccess
) {
}
