package com.oak.dms.encryption;

import com.oak.dms.storage.service.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Wraps a storage adapter in {@link EncryptingObjectStorageService} - used
 * by {@link com.oak.dms.storage.service.ObjectStorageServiceFactory} so
 * every caller that resolves storage gets encryption at rest without
 * knowing about it.
 */
@Component
@RequiredArgsConstructor
public class StorageEncryption {

    private final EncryptionProperties properties;
    private final KeyServices keys;
    private final ObjectKeyRecords records;
    private final ObjectCipher cipher;
    private final DataKeyCache cache;

    public EncryptingObjectStorageService wrap(ObjectStorageService adapter) {
        return new EncryptingObjectStorageService(adapter, keys, records, cipher, properties, cache);
    }
}
