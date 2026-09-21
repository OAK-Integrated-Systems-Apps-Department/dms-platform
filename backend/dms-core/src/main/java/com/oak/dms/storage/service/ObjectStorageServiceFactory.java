package com.oak.dms.storage.service;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageConfigurationException;
import org.springframework.stereotype.Component;

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
 */
@Component
public class ObjectStorageServiceFactory {

    private final Map<StorageProvider, ObjectStorageService> adaptersByProvider;

    public ObjectStorageServiceFactory(List<ObjectStorageService> adapters) {
        this.adaptersByProvider = adapters.stream()
                .collect(Collectors.toMap(ObjectStorageService::supportedProvider, Function.identity()));
    }

    public ObjectStorageService resolve(StorageLocation location) {
        ObjectStorageService adapter = adaptersByProvider.get(location.getProvider());
        if (adapter == null) {
            throw new StorageConfigurationException(
                    "No ObjectStorageService is registered for provider " + location.getProvider());
        }
        return adapter;
    }
}
