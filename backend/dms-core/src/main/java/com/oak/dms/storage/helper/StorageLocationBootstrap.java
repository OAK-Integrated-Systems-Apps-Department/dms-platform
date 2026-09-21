package com.oak.dms.storage.helper;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.repository.StorageLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently creates the single platform-wide default {@link StorageLocation}
 * ({@code tenantId} null) from {@link StorageProperties} on startup, if none
 * exists yet - this is what makes {@code STORAGE_PROVIDER=FILE_SYSTEM} (or
 * {@code =S3}) work with zero manual database setup ("OAK provides storage
 * for the client"). Deliberately a runner, not a Flyway seed migration: a
 * migration runs once and can never re-sync if the env vars change on a
 * later deploy, whereas this only fills the gap when the row is actually
 * missing and otherwise leaves an existing row (and its column values)
 * alone - it never overwrites one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageLocationBootstrap implements ApplicationRunner {

    private static final String DEFAULT_LOCATION_NAME = "platform-default";

    private final StorageLocationRepository storageLocationRepository;
    private final StorageProperties storageProperties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (storageLocationRepository.existsByTenantIdIsNullAndDeletedFalse()) {
            return;
        }

        StorageProvider provider = storageProperties.getDefaultProvider();
        StorageLocation.StorageLocationBuilder location = StorageLocation.builder()
                .name(DEFAULT_LOCATION_NAME)
                .provider(provider)
                .status(StorageLocation.Status.active);

        if (provider == StorageProvider.S3) {
            location.endpoint(storageProperties.getS3().getEndpoint())
                    .region(storageProperties.getS3().getRegion())
                    .bucket(storageProperties.getS3().getBucket())
                    .pathStyleAccess(storageProperties.getS3().isPathStyleAccess());
        } else {
            location.basePath(storageProperties.getFilesystem().getBasePath());
        }

        StorageLocation saved = storageLocationRepository.save(location.build());
        log.info("Created platform-default storage location {} (provider={})",
                saved.getStorageLocationId(), saved.getProvider());
    }
}
