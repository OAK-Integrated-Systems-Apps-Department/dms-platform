package com.oak.dms.scanning.service;

import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.exception.ScanConfigurationException;
import com.oak.dms.scanning.repository.ScanEngineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the effective {@link ScanEngine} for a tenant: that tenant's own
 * active engine if it has one, otherwise the platform-wide default row
 * ({@code tenantId IS NULL}) seeded by {@code ScanEngineBootstrap}. Mirrors
 * {@link com.oak.dms.storage.service.StorageLocationResolver} - the fallback
 * rule lives in exactly one place.
 */
@Component
@RequiredArgsConstructor
public class ScanEngineResolver {

    private final ScanEngineRepository scanEngineRepository;

    public ScanEngine resolveForTenant(UUID tenantId) {
        return scanEngineRepository
                .findFirstByTenantIdAndStatusAndDeletedFalse(tenantId, ScanEngine.Status.active)
                .orElseGet(() -> scanEngineRepository
                        .findPlatformDefault(ScanEngine.Status.active)
                        .orElseThrow(() -> new ScanConfigurationException(
                                "No scan engine is configured for tenant " + tenantId
                                        + " and no platform-default scan engine exists")));
    }

    /** The engine a scan result was dispatched to - retries stay on the same engine even if the tenant's engine changes. */
    public ScanEngine resolveById(UUID scanEngineId) {
        return scanEngineRepository.findByScanEngineIdAndDeletedFalse(scanEngineId)
                .orElseThrow(() -> new ScanConfigurationException("Scan engine " + scanEngineId + " no longer exists"));
    }
}
