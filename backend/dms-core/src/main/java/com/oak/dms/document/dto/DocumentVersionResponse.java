package com.oak.dms.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deliberately excludes {@code storageLocationId}/{@code storageObjectKey} -
 * internal storage-layer detail a caller never needs and should never be
 * able to guess (see docs/storage-architecture.md's tenant-isolation
 * section: the object key is never the security boundary, but there's no
 * reason to hand it out either).
 */
public record DocumentVersionResponse(
        UUID versionId,
        UUID documentId,
        int versionNumber,
        int majorVersion,
        int minorVersion,
        String versionLabel,
        String originalFilename,
        String mimeType,
        String fileHash,
        long sizeBytes,
        String versionComment,
        String createdReason,
        String scanStatus,
        String renditionStatus,
        String ocrStatus,
        OffsetDateTime createdAt
) {
}
