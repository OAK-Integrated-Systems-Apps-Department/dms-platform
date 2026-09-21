package com.oak.dms.storage.service;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the effective {@link StorageLocation} for a tenant: that
 * tenant's own registered location if it has one, otherwise the
 * platform-wide default row ({@code tenantId IS NULL}) seeded by
 * {@link com.oak.dms.storage.helper.StorageLocationBootstrap}. {@code DocumentServiceImpl} calls this
 * rather than querying {@link StorageLocationRepository} directly, so the
 * fallback rule lives in exactly one place.
 */
@Component
@RequiredArgsConstructor
public class StorageLocationResolver {

    private final StorageLocationRepository storageLocationRepository;

    public StorageLocation resolveForTenant(UUID tenantId) {
        return storageLocationRepository
                .findFirstByTenantIdAndStatusAndDeletedFalse(tenantId, StorageLocation.Status.active)
                .orElseGet(() -> storageLocationRepository
                        .findFirstPlatformDefault(StorageLocation.Status.active)
                        .orElseThrow(() -> new StorageConfigurationException(
                                "No storage location is configured for tenant " + tenantId
                                        + " and no platform-default location exists")));
    }
}
