package com.oak.dms.storage.service;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the effective {@link StorageLocation} for a tenant and
 * {@link StoragePurpose}: that tenant's own registered location for the
 * purpose if it has one, otherwise the platform-wide default row
 * ({@code tenantId IS NULL}) for that purpose, seeded by
 * {@link com.oak.dms.storage.helper.StorageLocationBootstrap}. Callers use
 * this rather than querying {@link StorageLocationRepository} directly, so
 * the fallback rule lives in exactly one place.
 * <p>
 * Staging and quarantine locations are only ever returned when asked for
 * explicitly - {@link #resolveForTenant(UUID)} is always {@code document}.
 */
@Component
@RequiredArgsConstructor
public class StorageLocationResolver {

    private final StorageLocationRepository storageLocationRepository;

    public StorageLocation resolveForTenant(UUID tenantId) {
        return resolveForTenant(tenantId, StoragePurpose.document);
    }

    public StorageLocation resolveForTenant(UUID tenantId, StoragePurpose purpose) {
        return storageLocationRepository
                .findFirstByTenantIdAndPurposeAndStatusAndDeletedFalse(tenantId, purpose, StorageLocation.Status.active)
                .orElseGet(() -> storageLocationRepository
                        .findPlatformDefault(purpose, StorageLocation.Status.active)
                        .orElseThrow(() -> new StorageConfigurationException(
                                "No " + purpose + " storage location is configured for tenant " + tenantId
                                        + " and no platform-default " + purpose + " location exists")));
    }
}
