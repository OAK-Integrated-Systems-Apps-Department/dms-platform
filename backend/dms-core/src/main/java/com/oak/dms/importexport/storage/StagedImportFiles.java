package com.oak.dms.importexport.storage;

import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and cleaning up the files bulk import stages for its rows (see
 * {@code BulkImportServiceImpl#receiveFile}). Once a staged file is no
 * longer needed - its batch was cancelled or finished, or the scan gate has
 * taken its own copy - the row's three staged columns are cleared and the
 * object is deleted, but only after the transaction commits, so a rollback
 * never loses a file the row still points at.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StagedImportFiles {

    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;

    /**
     * Opens the item's staged file. The caller must close the stream.
     *
     * @throws IllegalStateException if the item has no staged file or its
     *                               storage location is gone
     * @throws com.oak.dms.storage.exception.StorageObjectNotFoundException
     *                               if the object itself is missing
     */
    public DownloadedObject open(ImportItem item) {
        if (item.getStagedObjectKey() == null) {
            throw new IllegalStateException("Import item " + item.getImportItemId() + " has no staged file");
        }
        StorageLocation location = storageLocationRepository
                .findByStorageLocationIdAndDeletedFalse(item.getStagedStorageLocationId())
                .orElseThrow(() -> new IllegalStateException("Storage location "
                        + item.getStagedStorageLocationId() + " for import item " + item.getImportItemId()
                        + " not found"));
        return objectStorageServiceFactory.resolve(location).download(location, item.getStagedObjectKey());
    }

    /**
     * Clears the staged columns on every item that has a staged file (the
     * caller saves the items) and, once the surrounding transaction
     * commits, deletes those objects. Best-effort: a failed delete, or a
     * storage location that no longer exists, is logged and leaves an
     * orphaned object behind.
     *
     * @return how many staged files were scheduled for deletion
     */
    public int clearAndDeleteAfterCommit(Collection<ImportItem> items) {
        List<StagedObject> staged = new ArrayList<>();
        Map<UUID, Optional<StorageLocation>> locations = new HashMap<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (ImportItem item : items) {
            if (item.getStagedObjectKey() == null) {
                continue;
            }
            String objectKey = item.getStagedObjectKey();
            UUID locationId = item.getStagedStorageLocationId();
            locations.computeIfAbsent(locationId, storageLocationRepository::findByStorageLocationIdAndDeletedFalse)
                    .ifPresentOrElse(location -> staged.add(new StagedObject(location, objectKey)),
                            () -> log.warn("Storage location {} for staged import object {} not found; object left"
                                    + " in place", locationId, objectKey));
            item.setStagedObjectKey(null);
            item.setStagedStorageLocationId(null);
            item.setStagedSizeBytes(null);
            item.setUpdatedAt(now);
        }
        deleteAfterCommit(staged);
        return staged.size();
    }

    private void deleteAfterCommit(List<StagedObject> staged) {
        if (staged.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (StagedObject object : staged) {
                    try {
                        objectStorageServiceFactory.resolve(object.location())
                                .delete(object.location(), object.objectKey());
                    } catch (RuntimeException e) {
                        log.warn("Failed to delete staged import object {}", object.objectKey(), e);
                    }
                }
            }
        });
    }

    private record StagedObject(StorageLocation location, String objectKey) {
    }
}
