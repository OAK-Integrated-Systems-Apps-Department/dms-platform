package com.oak.dms.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentResponse(
        UUID documentId,
        UUID tenantId,
        UUID documentTypeId,
        /** Null when the type has since been deleted. Embedded so viewers without a document-type permission still see it. */
        String documentTypeName,
        String title,
        UUID currentVersionId,
        UUID latestVersionId,
        UUID publishedVersionId,
        UUID ownerUserId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID folderId,
        /** The current-or-latest version's file, so the detail page can preview and download it. Null if it has no version yet. */
        String fileName,
        String mimeType,
        Long sizeBytes,
        Integer versionNumber,
        /** The owner's department (V123); null when the owner had none. */
        UUID ownerDepartmentId,
        /** assigned, or holding while the document waits for a new owner (V123). */
        String ownershipState,
        /** Set together (V105) once a workflow with a valid signature approved - null means not locked. */
        OffsetDateTime signatureLockedAt,
        UUID signatureLockedVersionId
) {
}
