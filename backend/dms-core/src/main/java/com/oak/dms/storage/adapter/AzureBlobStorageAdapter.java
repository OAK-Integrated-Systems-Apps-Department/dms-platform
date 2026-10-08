package com.oak.dms.storage.adapter;

import com.azure.core.http.rest.Response;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.BlockBlobItem;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.specialized.BlobInputStream;
import com.azure.storage.common.StorageSharedKeyCredential;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to Azure Blob Storage - the counterpart to {@link S3ObjectStorageAdapter}/
 * {@link FileSystemStorageAdapter}, registered the same way via
 * {@link com.oak.dms.storage.service.ObjectStorageServiceFactory}. Builds and
 * caches one {@link BlobContainerClient} per {@link StorageLocation} (different
 * locations can have different endpoint/container/credentials), rather than a
 * single deployment-wide client - same reasoning as {@code S3ObjectStorageAdapter}'s
 * own client cache. Never logs a resolved account key or a generated SAS URL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AzureBlobStorageAdapter implements ObjectStorageService {

    private final StorageProperties storageProperties;
    private final Environment environment;

    // Package-private (not private) so AzureBlobStorageAdapterTest can seed a
    // mock BlobContainerClient directly for a given location, bypassing the
    // real builder in containerClient() below entirely - same-package test
    // seam only, not a public API. Mirrors S3ObjectStorageAdapter's clients field.
    final ConcurrentHashMap<UUID, BlobContainerClient> containerClients = new ConcurrentHashMap<>();

    @Override
    public StorageProvider supportedProvider() {
        return StorageProvider.AZURE_BLOB;
    }

    @Override
    public StoredObjectMetadata upload(StorageLocation location, String objectKey, InputStream content,
                                        long contentLength, String contentType) {
        BlobClient blobClient = containerClient(location).getBlobClient(objectKey);
        try {
            BlobParallelUploadOptions options = new BlobParallelUploadOptions(content);
            if (contentType != null && !contentType.isBlank()) {
                options.setHeaders(new BlobHttpHeaders().setContentType(contentType));
            }
            Response<BlockBlobItem> response = blobClient.uploadWithResponse(options, null, Context.NONE);
            BlockBlobItem item = response.getValue();
            return new StoredObjectMetadata(objectKey, contentLength, contentType,
                    stripQuotes(item.getETag()), item.getLastModified());
        } catch (Exception e) {
            log.warn("Azure Blob upload failed for object {} at storage location {}", objectKey,
                    location.getStorageLocationId());
            throw new StorageUploadException(objectKey, e);
        }
    }

    @Override
    public DownloadedObject download(StorageLocation location, String objectKey) {
        BlobClient blobClient = containerClient(location).getBlobClient(objectKey);
        try {
            BlobInputStream stream = blobClient.openInputStream();
            BlobProperties properties = stream.getProperties();
            return new DownloadedObject(stream, properties.getBlobSize(), properties.getContentType());
        } catch (BlobStorageException e) {
            if (isNotFound(e)) {
                throw new StorageObjectNotFoundException(objectKey);
            }
            log.warn("Azure Blob download failed for object {} at storage location {}", objectKey,
                    location.getStorageLocationId());
            throw new StorageDownloadException(objectKey, e);
        } catch (Exception e) {
            log.warn("Azure Blob download failed for object {} at storage location {}", objectKey,
                    location.getStorageLocationId());
            throw new StorageDownloadException(objectKey, e);
        }
    }

    @Override
    public void delete(StorageLocation location, String objectKey) {
        BlobClient blobClient = containerClient(location).getBlobClient(objectKey);
        try {
            blobClient.deleteIfExists();
        } catch (Exception e) {
            log.warn("Azure Blob delete failed for object {} at storage location {}", objectKey,
                    location.getStorageLocationId());
            throw new StorageDeleteException(objectKey, e);
        }
    }

    @Override
    public boolean exists(StorageLocation location, String objectKey) {
        BlobClient blobClient = containerClient(location).getBlobClient(objectKey);
        try {
            Boolean exists = blobClient.exists();
            return exists != null && exists;
        } catch (Exception e) {
            log.warn("Azure Blob existence check failed for {} at storage location {}", objectKey,
                    location.getStorageLocationId());
            throw new StorageDownloadException(objectKey, e);
        }
    }

    @Override
    public StoredObjectMetadata getMetadata(StorageLocation location, String objectKey) {
        BlobClient blobClient = containerClient(location).getBlobClient(objectKey);
        try {
            BlobProperties properties = blobClient.getProperties();
            return new StoredObjectMetadata(objectKey, properties.getBlobSize(), properties.getContentType(),
                    stripQuotes(properties.getETag()), properties.getLastModified());
        } catch (BlobStorageException e) {
            if (isNotFound(e)) {
                throw new StorageObjectNotFoundException(objectKey);
            }
            log.warn("Azure Blob get-properties failed for {} at storage location {}", objectKey,
                    location.getStorageLocationId());
            throw new StorageDownloadException(objectKey, e);
        } catch (Exception e) {
            log.warn("Azure Blob get-properties failed for {} at storage location {}", objectKey,
                    location.getStorageLocationId());
            throw new StorageDownloadException(objectKey, e);
        }
    }

    /** Azure Storage Service Encryption is always on (AES-256) and can't be turned off. */
    @Override
    public boolean encryptsAtRest(StorageLocation location) {
        return true;
    }

    @Override
    public Optional<String> generatePresignedDownloadUrl(StorageLocation location, String objectKey, Duration ttl) {
        BlobClient blobClient = containerClient(location).getBlobClient(objectKey);
        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(
                OffsetDateTime.now().plus(ttl), new BlobSasPermission().setReadPermission(true));
        String sas = blobClient.generateSas(sasValues);
        // Deliberately not logged - see this class's own doc comment.
        return Optional.of(blobClient.getBlobUrl() + "?" + sas);
    }

    private static boolean isNotFound(BlobStorageException e) {
        return e.getStatusCode() == 404
                || BlobErrorCode.BLOB_NOT_FOUND.equals(e.getErrorCode())
                || BlobErrorCode.RESOURCE_NOT_FOUND.equals(e.getErrorCode());
    }

    // Package-private, same reason as the `containerClients` field above -
    // lets AzureBlobStorageAdapterTest verify the endpoint/credential builder
    // wiring succeeds without performing a real Azure operation (which would
    // require a live account).
    BlobContainerClient containerClient(StorageLocation location) {
        return containerClients.computeIfAbsent(location.getStorageLocationId(), id -> {
            BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
                    .endpoint(requireEndpoint(location))
                    .credential(resolveCredentials(location));
            BlobServiceClient serviceClient = builder.buildClient();
            return serviceClient.getBlobContainerClient(requireContainer(location));
        });
    }

    private String requireEndpoint(StorageLocation location) {
        String endpoint = location.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = storageProperties.getAzureBlob().getEndpoint();
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new StorageConfigurationException(
                    "Storage location " + location.getStorageLocationId() + " has no Azure Blob endpoint configured");
        }
        return endpoint;
    }

    private String requireContainer(StorageLocation location) {
        String container = location.getBucket();
        if (container == null || container.isBlank()) {
            container = storageProperties.getAzureBlob().getContainer();
        }
        if (container == null || container.isBlank()) {
            throw new StorageConfigurationException(
                    "Storage location " + location.getStorageLocationId() + " has no Azure Blob container configured");
        }
        return container;
    }

    /**
     * {@code credentialRef} is never a raw account key - it names an
     * environment variable pair ({@code STORAGE_CRED_<ref>_ACCOUNT_NAME}/
     * {@code _ACCOUNT_KEY}) this method resolves at call time. A blank ref
     * falls back to this deployment's single {@code dms.storage.azure-blob.*}
     * configuration, covering both the platform-default location and any
     * tenant content with the deployment's one configured Azure backend.
     */
    private StorageSharedKeyCredential resolveCredentials(StorageLocation location) {
        String ref = location.getCredentialRef();
        String accountName;
        String accountKey;
        if (ref == null || ref.isBlank()) {
            accountName = storageProperties.getAzureBlob().getAccountName();
            accountKey = storageProperties.getAzureBlob().getAccountKey();
        } else {
            String prefix = "STORAGE_CRED_" + ref.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
            accountName = environment.getProperty(prefix + "ACCOUNT_NAME");
            accountKey = environment.getProperty(prefix + "ACCOUNT_KEY");
        }
        if (accountName == null || accountName.isBlank() || accountKey == null || accountKey.isBlank()) {
            throw new StorageConfigurationException(
                    "No Azure Blob credentials resolved for storage location " + location.getStorageLocationId()
                            + " (credentialRef=" + (ref == null || ref.isBlank() ? "<deployment-default>" : ref) + ")");
        }
        return new StorageSharedKeyCredential(accountName, accountKey);
    }

    private static String stripQuotes(String eTag) {
        return eTag == null ? null : eTag.replace("\"", "");
    }
}
