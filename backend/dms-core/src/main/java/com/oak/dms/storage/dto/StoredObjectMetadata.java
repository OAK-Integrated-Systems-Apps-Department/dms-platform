package com.oak.dms.storage.dto;

import java.time.OffsetDateTime;

/**
 * Provider-independent metadata about a stored object, returned by
 * {@code ObjectStorageService.upload}/{@code getMetadata} - never an AWS
 * SDK {@code PutObjectResponse}/{@code HeadObjectResponse} or any other
 * provider-specific type.
 */
public record StoredObjectMetadata(
        String objectKey,
        long sizeBytes,
        String contentType,
        /**
         * Best-effort integrity token the provider itself returns (e.g. S3's
         * ETag) - nullable, provider-dependent, and NOT guaranteed to be a
         * content hash (S3 multipart uploads produce a composite, non-MD5
         * ETag; filesystem storage has no native concept at all, so this is
         * always null there). This is never the canonical file hash -
         * {@code DocumentVersion.fileHash} is computed independently by the
         * caller before {@code upload} is ever called.
         */
        String contentHash,
        OffsetDateTime lastModified
) {
}
