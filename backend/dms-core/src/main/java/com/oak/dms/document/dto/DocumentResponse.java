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
        OffsetDateTime updatedAt
) {
}
