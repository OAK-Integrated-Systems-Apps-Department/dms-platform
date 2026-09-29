package com.oak.dms.storage.service;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectStorageServiceFactoryTest {

    @Test
    void resolve_returnsTheAdapterMatchingTheLocationsProvider() {
        ObjectStorageService s3Adapter = stubAdapter(StorageProvider.S3);
        ObjectStorageService fsAdapter = stubAdapter(StorageProvider.FILE_SYSTEM);
        ObjectStorageServiceFactory factory = new ObjectStorageServiceFactory(List.of(s3Adapter, fsAdapter));

        assertThat(factory.resolve(locationWithProvider(StorageProvider.S3))).isSameAs(s3Adapter);
        assertThat(factory.resolve(locationWithProvider(StorageProvider.FILE_SYSTEM))).isSameAs(fsAdapter);
    }

    @Test
    void resolve_noAdapterRegisteredForProvider_throwsConfigurationException() {
        ObjectStorageServiceFactory factory = new ObjectStorageServiceFactory(List.of(stubAdapter(StorageProvider.S3)));

        assertThatThrownBy(() -> factory.resolve(locationWithProvider(StorageProvider.FILE_SYSTEM)))
                .isInstanceOf(StorageConfigurationException.class);
    }

    private static ObjectStorageService stubAdapter(StorageProvider provider) {
        ObjectStorageService adapter = mock(ObjectStorageService.class);
        when(adapter.supportedProvider()).thenReturn(provider);
        return adapter;
    }

    private static StorageLocation locationWithProvider(StorageProvider provider) {
        return StorageLocation.builder().storageLocationId(UUID.randomUUID()).provider(provider).build();
    }
}
