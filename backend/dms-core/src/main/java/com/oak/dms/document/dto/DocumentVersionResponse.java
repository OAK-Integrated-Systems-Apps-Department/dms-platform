package com.oak.dms.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentVersionResponse(
        UUID documentId,
        UUID versionId,
        String title,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        String scanStatus,
        String ocrStatus,
        OffsetDateTime createdAt) {
}
