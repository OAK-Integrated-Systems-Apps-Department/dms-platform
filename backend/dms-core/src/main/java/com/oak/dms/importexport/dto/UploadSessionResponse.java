package com.oak.dms.importexport.dto;

import com.oak.dms.importexport.enums.UploadSessionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A resumable upload. To resume, send the next chunk starting at
 * {@code receivedBytes}; each chunk may be at most {@code maxChunkBytes}.
 */
public record UploadSessionResponse(
        UUID uploadSessionId,
        String fileName,
        long totalSizeBytes,
        long receivedBytes,
        UploadSessionStatus status,
        long maxChunkBytes,
        OffsetDateTime expiresAt
) {
}
