package com.oak.dms.storage.adapter;

import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.exception.StorageDeleteException;
import com.oak.dms.storage.exception.StorageDownloadException;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.exception.StorageUploadException;
import com.oak.dms.storage.helper.StorageProperties;
import com.oak.dms.storage.service.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Storage adapter for a client-provided mounted/local/on-prem filesystem
 * (e.g. an NFS mount) - {@code java.nio.file} is used here and ONLY here;
 * {@code DocumentService} never imports {@code File}/{@code Path}/
 * {@code Files} directly, matching {@link ObjectStorageService}'s own
 * contract. Every operation confines itself to {@code location.getBasePath()}:
 * the object key is resolved and normalized against it, and explicitly
 * verified not to escape it before any read/write, blocking path traversal
 * regardless of what an object key happens to contain.
 */
@Component
@RequiredArgsConstructor
public class FileSystemStorageAdapter implements ObjectStorageService {

    private final StorageProperties storageProperties;

    @Override
    public StorageProvider supportedProvider() {
        return StorageProvider.FILE_SYSTEM;
    }

    @Override
    public StoredObjectMetadata upload(StorageLocation location, String objectKey, InputStream content,
                                        long contentLength, String contentType) {
        Path target = resolveAndValidate(location, objectKey);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                content.transferTo(out);
            }
            return new StoredObjectMetadata(objectKey, Files.size(target), contentType, null, OffsetDateTime.now());
        } catch (IOException e) {
            throw new StorageUploadException(objectKey, e);
        }
    }

    @Override
    public DownloadedObject download(StorageLocation location, String objectKey) {
        Path target = resolveAndValidate(location, objectKey);
        if (!Files.isRegularFile(target)) {
            throw new StorageObjectNotFoundException(objectKey);
        }
        try {
            long size = Files.size(target);
            InputStream in = Files.newInputStream(target, StandardOpenOption.READ);
            return new DownloadedObject(in, size, probeContentType(target));
        } catch (IOException e) {
            throw new StorageDownloadException(objectKey, e);
        }
    }

    @Override
    public void delete(StorageLocation location, String objectKey) {
        Path target = resolveAndValidate(location, objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new StorageDeleteException(objectKey, e);
        }
    }

    @Override
    public boolean exists(StorageLocation location, String objectKey) {
        return Files.isRegularFile(resolveAndValidate(location, objectKey));
    }

    @Override
    public StoredObjectMetadata getMetadata(StorageLocation location, String objectKey) {
        Path target = resolveAndValidate(location, objectKey);
        if (!Files.isRegularFile(target)) {
            throw new StorageObjectNotFoundException(objectKey);
        }
        try {
            long size = Files.size(target);
            OffsetDateTime lastModified = Files.getLastModifiedTime(target).toInstant().atOffset(ZoneOffset.UTC);
            return new StoredObjectMetadata(objectKey, size, probeContentType(target), null, lastModified);
        } catch (IOException e) {
            throw new StorageDownloadException(objectKey, e);
        }
    }

    /** Filesystem storage has no client-facing URL mechanism in this pass - always empty. */
    @Override
    public Optional<String> generatePresignedDownloadUrl(StorageLocation location, String objectKey, Duration ttl) {
        return Optional.empty();
    }

    private Path resolveAndValidate(StorageLocation location, String objectKey) {
        String basePathValue = location.getBasePath();
        if (basePathValue == null || basePathValue.isBlank()) {
            basePathValue = storageProperties.getFilesystem().getBasePath();
        }
        if (basePathValue == null || basePathValue.isBlank()) {
            throw new StorageConfigurationException(
                    "Storage location " + location.getStorageLocationId() + " has no filesystem base path configured");
        }
        Path base = Paths.get(basePathValue).toAbsolutePath().normalize();
        Path resolved = base.resolve(objectKey).normalize();
        if (!resolved.startsWith(base)) {
            throw new StorageConfigurationException("Object key would escape the storage location's base path: " + objectKey);
        }
        return resolved;
    }

    /**
     * Best-effort guess only, same caveat as {@link StoredObjectMetadata#contentHash()} -
     * the authoritative mime type lives on {@code DocumentVersion.mimeType},
     * set independently by the caller at upload time.
     */
    private static String probeContentType(Path path) {
        try {
            String type = Files.probeContentType(path);
            return type != null ? type : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }
}
