package com.oak.dms.storage.service;

import com.oak.dms.storage.dto.CreateStorageLocationRequest;
import com.oak.dms.storage.dto.StorageLocationResponse;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped CRUD over {@link com.oak.dms.storage.entity.StorageLocation}. Deliberately create/get/
 * list only in this pass - update/deactivate are a documented limitation,
 * not an oversight (see docs/storage-architecture.md).
 */
public interface StorageLocationService {

    StorageLocationResponse createLocation(CreateStorageLocationRequest request);

    StorageLocationResponse getLocation(UUID storageLocationId);

    List<StorageLocationResponse> listLocations();
}
