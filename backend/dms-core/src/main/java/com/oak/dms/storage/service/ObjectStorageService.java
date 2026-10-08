package com.oak.dms.storage.service;

import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageException;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * The one storage abstraction {@code DocumentService} depends on. Every
 * method takes the {@link StorageLocation} to act against alongside the
 * object key, so an implementation has everything it needs to connect
 * without the caller knowing any connection detail (endpoint, credentials,
 * base path, ...) - those stay entirely inside the adapter.
 * <p>
 * Never exposes an AWS SDK type, a MinIO/SeaweedFS-specific type, or
 * {@code java.io.File}/{@code Path}/{@code Files} to a caller. Every
 * failure surfaces as a {@link StorageException} subclass.
 * <p>
 * Two beans implement this ({@link com.oak.dms.storage.adapter.S3ObjectStorageAdapter},
 * {@link com.oak.dms.storage.adapter.FileSystemStorageAdapter}); {@link ObjectStorageServiceFactory}
 * picks the right one per {@code location.getProvider()} - callers should
 * go through the factory, never inject a specific adapter directly.
 */
public interface ObjectStorageService {

    StorageProvider supportedProvider();

    /** @param contentType may be null if unknown - implementations should fall back to a generic default. */
    StoredObjectMetadata upload(StorageLocation location, String objectKey, InputStream content,
                                 long contentLength, String contentType);

    /** @throws StorageObjectNotFoundException if {@code objectKey} doesn't exist at {@code location}. */
    DownloadedObject download(StorageLocation location, String objectKey);

    /** A no-op (not an error) if {@code objectKey} doesn't exist. */
    void delete(StorageLocation location, String objectKey);

    boolean exists(StorageLocation location, String objectKey);

    /** @throws StorageObjectNotFoundException if {@code objectKey} doesn't exist at {@code location}. */
    StoredObjectMetadata getMetadata(StorageLocation location, String objectKey);

    /**
     * Whether objects written to this location are encrypted at rest by the storage itself - server-side
     * encryption. Required for a staging area a cloud malware scanner reads in place, which the
     * application can't encrypt (see ObjectStorageServiceFactory#resolveUnencrypted).
     */
    default boolean encryptsAtRest(StorageLocation location) {
        return false;
    }

    /** Empty when this provider doesn't support a client-facing presigned URL (e.g. plain filesystem storage). */
    Optional<String> generatePresignedDownloadUrl(StorageLocation location, String objectKey, Duration ttl);
}
