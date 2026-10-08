package com.oak.dms.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentResponse(
        UUID documentId,
        UUID tenantId,
        UUID documentTypeId,
        String title,
        UUID currentVersionId,
        UUID latestVersionId,
        UUID publishedVersionId,
        UUID ownerUserId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID folderId
) {
}
