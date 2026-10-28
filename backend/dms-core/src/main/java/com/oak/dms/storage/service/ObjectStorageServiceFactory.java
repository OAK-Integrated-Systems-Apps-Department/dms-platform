package com.oak.dms.storage.service;

import com.oak.dms.encryption.StorageEncryption;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageConfigurationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link ObjectStorageService} bean matching a
 * {@link StorageLocation}'s {@link StorageProvider} - the one place this
 * application branches on storage provider. {@code DocumentService} and
 * every other business-logic caller goes through {@link #resolve} rather
 * than injecting {@link com.oak.dms.storage.adapter.S3ObjectStorageAdapter}/{@link com.oak.dms.storage.adapter.FileSystemStorageAdapter}
 * directly, so adding a new provider (a new adapter bean + a new
 * {@link StorageProvider} value) never touches business logic.
 * <p>
 * {@link #resolve} also adds encryption at rest (Module DE): what it returns
 * encrypts every upload and decrypts every download - see
 * {@link com.oak.dms.encryption.EncryptingObjectStorageService}.
 */
@Component
public class ObjectStorageServiceFactory {

    private final Map<StorageProvider, ObjectStorageService> adaptersByProvider;
    private final Map<StorageProvider, ObjectStorageService> encryptedByProvider;

    /** No encryption - tests that exercise the adapters themselves. */
    public ObjectStorageServiceFactory(List<ObjectStorageService> adapters) {
        this(adapters, null);
    }

    @Autowired
    public ObjectStorageServiceFactory(List<ObjectStorageService> adapters, StorageEncryption encryption) {
        this.adaptersByProvider = adapters.stream()
                .collect(Collectors.toMap(ObjectStorageService::supportedProvider, Function.identity()));
        this.encryptedByProvider = new EnumMap<>(StorageProvider.class);
        // Always wrapped when encryption is wired in: even with it turned off, objects that are
        // already encrypted must still be decrypted on the way out.
        adaptersByProvider.forEach((provider, adapter) -> encryptedByProvider.put(provider,
                encryption != null ? encryption.wrap(adapter) : adapter));
    }

    /** The storage for a location, encrypting at rest. */
    public ObjectStorageService resolve(StorageLocation location) {
        return lookup(encryptedByProvider, location);
    }

    /**
     * The storage for a location WITHOUT encryption - only for an object a
     * cloud malware scanner must read in place (a staging upload awaiting
     * AWS GuardDuty / Defender for Storage). Reading such an object later
     * through {@link #resolve} still works: it has no key, so it passes through.
     */
    public ObjectStorageService resolveUnencrypted(StorageLocation location) {
        return lookup(adaptersByProvider, location);
    }

    /**
     * {@link #resolveUnencrypted} for a cloud malware scanner's staging area - which must then be
     * encrypted by the storage itself (S3 SSE-S3/SSE-KMS; Azure always is), so no upload ever sits
     * unencrypted at rest. Refuses a location without it.
     */
    public ObjectStorageService resolveForInPlaceScan(StorageLocation location) {
        ObjectStorageService adapter = resolveUnencrypted(location);
        if (!adapter.encryptsAtRest(location)) {
            throw new StorageConfigurationException("Staging location " + location.getStorageLocationId()
                    + " is read in place by a cloud malware scanner, so it must be encrypted by the storage: set "
                    + "server-side encryption (SCAN_STAGING_SSE=aws:kms or AES256, or \"serverSideEncryption\" in "
                    + "the location's configuration)");
        }
        return adapter;
    }

    private static ObjectStorageService lookup(Map<StorageProvider, ObjectStorageService> services,
                                               StorageLocation location) {
        ObjectStorageService adapter = services.get(location.getProvider());
        if (adapter == null) {
            throw new StorageConfigurationException(
                    "No ObjectStorageService is registered for provider " + location.getProvider());
        }
        return adapter;
    }
}
