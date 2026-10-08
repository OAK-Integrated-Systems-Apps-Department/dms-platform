package com.oak.dms.encryption;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Writes storage_object_keys rows in their own transactions. An object and
 * its key row must stay together whatever the caller's transaction does -
 * a rollback after the object was written must not leave ciphertext with
 * no key (unreadable forever) - so they never join it.
 */
@Component
@RequiredArgsConstructor
class ObjectKeyRecords {

    private final StorageObjectKeyRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<StorageObjectKey> find(UUID locationId, String objectKey) {
        return repository.findByStorageLocationIdAndObjectKey(locationId, objectKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StorageObjectKey save(StorageObjectKey row) {
        return repository.saveAndFlush(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(UUID locationId, String objectKey) {
        repository.deleteObject(locationId, objectKey);
    }
}
