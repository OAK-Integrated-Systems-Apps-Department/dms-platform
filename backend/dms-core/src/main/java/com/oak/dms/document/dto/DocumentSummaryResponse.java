package com.oak.dms.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of the document list/picker (GET /api/v1/documents). documentTypeName is null for a deleted type. */
public record DocumentSummaryResponse(
        UUID documentId,
        String title,
        String documentTypeName,
        UUID latestVersionId,
        OffsetDateTime createdAt,
        String createdByName
) {
}
