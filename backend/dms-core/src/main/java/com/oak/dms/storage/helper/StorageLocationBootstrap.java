package com.oak.dms.storage.helper;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.repository.StorageLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

/**
 * Idempotently creates the platform-wide default {@link StorageLocation} rows
 * ({@code tenantId} null) - one per {@link StoragePurpose} - from
 * {@link StorageProperties} on startup, if they don't exist yet. This is what
 * makes {@code STORAGE_PROVIDER=FILE_SYSTEM} (or {@code =S3}) work with zero
 * manual database setup ("OAK provides storage for the client"), and what
 * gives the scan gate its staging and quarantine areas. Deliberately a
 * runner, not a Flyway seed migration: a migration runs once and can never
 * re-sync if the env vars change on a later deploy, whereas this only fills
 * the gap when a row is actually missing and otherwise leaves an existing
 * row (and its column values) alone - it never overwrites one.
 * <p>
 * After seeding, refuses to let the application start if the platform
 * staging or quarantine location resolves to the same physical target as the
 * document location (or each other) - same bucket/container, or a nested
 * filesystem base path. Quarantine isolation is enforced here, not advised.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageLocationBootstrap implements ApplicationRunner {

    private static final Map<StoragePurpose, String> DEFAULT_LOCATION_NAMES = Map.of(
            StoragePurpose.document, "platform-default",
            StoragePurpose.staging, "platform-staging",
            StoragePurpose.quarantine, "platform-quarantine");

    private final StorageLocationRepository storageLocationRepository;
    private final StorageProperties storageProperties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedDocumentLocation();
        seedPurposeLocation(StoragePurpose.staging, storageProperties.getStaging());
        seedPurposeLocation(StoragePurpose.quarantine, storageProperties.getQuarantine());
        verifyIsolation();
    }

    private void seedDocumentLocation() {
        if (storageLocationRepository.existsByTenantIdIsNullAndPurposeAndDeletedFalse(StoragePurpose.document)) {
            return;
        }

        StorageProvider provider = storageProperties.getDefaultProvider();
        StorageLocation.StorageLocationBuilder location = StorageLocation.builder()
                .name(DEFAULT_LOCATION_NAMES.get(StoragePurpose.document))
                .provider(provider)
                .purpose(StoragePurpose.document)
                .status(StorageLocation.Status.active);

        switch (provider) {
            case S3 -> location.endpoint(storageProperties.getS3().getEndpoint())
                    .region(storageProperties.getS3().getRegion())
                    .bucket(storageProperties.getS3().getBucket())
                    .pathStyleAccess(storageProperties.getS3().isPathStyleAccess());
            case AZURE_BLOB -> location.endpoint(storageProperties.getAzureBlob().getEndpoint())
                    .bucket(storageProperties.getAzureBlob().getContainer());
            case FILE_SYSTEM -> location.basePath(storageProperties.getFilesystem().getBasePath());
        }

        save(location.build());
    }

    private void seedPurposeLocation(StoragePurpose purpose, StorageProperties.PurposeTarget target) {
        if (storageLocationRepository.existsByTenantIdIsNullAndPurposeAndDeletedFalse(purpose)) {
            return;
        }

        StorageLocation.StorageLocationBuilder location = StorageLocation.builder()
                .name(DEFAULT_LOCATION_NAMES.get(purpose))
                .provider(target.getProvider())
                .purpose(purpose)
                .credentialRef(blankToNull(target.getCredentialRef()))
                .status(StorageLocation.Status.active);

        switch (target.getProvider()) {
            case S3 -> location.endpoint(blankToNull(target.getEndpoint()))
                    .region(blankToNull(target.getRegion()))
                    .bucket(blankToNull(target.getBucket()))
                    .pathStyleAccess(target.isPathStyleAccess());
            case AZURE_BLOB -> location.endpoint(blankToNull(target.getEndpoint()))
                    .bucket(blankToNull(target.getBucket()));
            case FILE_SYSTEM -> location.basePath(target.getBasePath());
        }

        save(location.build());
    }

    private void save(StorageLocation location) {
        StorageLocation saved = storageLocationRepository.save(location);
        log.info("Created platform-default {} storage location {} (provider={})",
                saved.getPurpose(), saved.getStorageLocationId(), saved.getProvider());
    }

    private void verifyIsolation() {
        StorageLocation document = platformDefault(StoragePurpose.document);
        StorageLocation staging = platformDefault(StoragePurpose.staging);
        StorageLocation quarantine = platformDefault(StoragePurpose.quarantine);
        if (document == null || staging == null || quarantine == null) {
            return;
        }
        requireDisjoint(document, staging);
        requireDisjoint(document, quarantine);
        requireDisjoint(staging, quarantine);
    }

    private StorageLocation platformDefault(StoragePurpose purpose) {
        return storageLocationRepository.findPlatformDefault(purpose, StorageLocation.Status.active).orElse(null);
    }

    private void requireDisjoint(StorageLocation a, StorageLocation b) {
        if (a.getProvider() != b.getProvider()) {
            return;
        }
        boolean overlaps = switch (a.getProvider()) {
            case FILE_SYSTEM -> {
                Path pathA = effectiveBasePath(a);
                Path pathB = effectiveBasePath(b);
                yield pathA.startsWith(pathB) || pathB.startsWith(pathA);
            }
            case S3 -> effectiveS3Target(a).equals(effectiveS3Target(b));
            case AZURE_BLOB -> effectiveAzureTarget(a).equals(effectiveAzureTarget(b));
        };
        if (overlaps) {
            throw new IllegalStateException("Platform " + a.getPurpose() + " storage location "
                    + a.getStorageLocationId() + " and " + b.getPurpose() + " storage location "
                    + b.getStorageLocationId() + " resolve to the same physical storage target - "
                    + "configure a separate bucket/container/base path (dms.storage.staging.* / "
                    + "dms.storage.quarantine.*) so quarantined and staged files stay isolated from documents");
        }
    }

    private Path effectiveBasePath(StorageLocation location) {
        String basePath = location.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            basePath = storageProperties.getFilesystem().getBasePath();
        }
        return Paths.get(basePath).toAbsolutePath().normalize();
    }

    private String effectiveS3Target(StorageLocation location) {
        String endpoint = orDefault(location.getEndpoint(), storageProperties.getS3().getEndpoint());
        String bucket = orDefault(location.getBucket(), storageProperties.getS3().getBucket());
        return (endpoint == null ? "aws" : endpoint.toLowerCase(Locale.ROOT)) + "|" + bucket;
    }

    private String effectiveAzureTarget(StorageLocation location) {
        String endpoint = orDefault(location.getEndpoint(), storageProperties.getAzureBlob().getEndpoint());
        String container = orDefault(location.getBucket(), storageProperties.getAzureBlob().getContainer());
        return (endpoint == null ? "" : endpoint.toLowerCase(Locale.ROOT)) + "|" + container;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? blankToNull(fallback) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
