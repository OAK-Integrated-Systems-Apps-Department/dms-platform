package com.oak.dms.scanning.service;

import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.exception.ScanConfigurationException;
import com.oak.dms.storage.entity.StorageLocation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link ScanService} bean matching a {@link ScanEngine}'s
 * {@link ScanProvider} - the one place this application branches on scan
 * provider. Mirrors {@link com.oak.dms.storage.service.ObjectStorageServiceFactory}.
 */
@Component
public class ScanEngineFactory {

    private final Map<ScanProvider, ScanService> adaptersByProvider;

    public ScanEngineFactory(List<ScanService> adapters) {
        this.adaptersByProvider = adapters.stream()
                .collect(Collectors.toMap(ScanService::supportedProvider, Function.identity()));
    }

    public ScanService resolve(ScanEngine engine) {
        ScanService adapter = adaptersByProvider.get(engine.getProvider());
        if (adapter == null) {
            throw new ScanConfigurationException("No ScanService is registered for provider " + engine.getProvider());
        }
        return adapter;
    }

    /**
     * Fails fast - before any upload bytes are read - when the staging location
     * can't be seen by the engine (e.g. GuardDuty with filesystem staging).
     */
    public ScanService resolveCompatible(ScanEngine engine, StorageLocation stagingLocation) {
        ScanService adapter = resolve(engine);
        adapter.requiredStagingProvider().ifPresent(required -> {
            if (stagingLocation.getProvider() != required) {
                throw new ScanConfigurationException("Scan engine " + engine.getScanEngineId() + " ("
                        + engine.getProvider() + ") needs a " + required + " staging location, but staging location "
                        + stagingLocation.getStorageLocationId() + " is " + stagingLocation.getProvider());
            }
        });
        return adapter;
    }
}
