package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.repository.ScanEngineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently creates the platform-wide default {@link ScanEngine}
 * ({@code tenantId} null) from {@link ScanProperties} on startup, if none
 * exists yet - mirrors {@link com.oak.dms.storage.helper.StorageLocationBootstrap}:
 * only fills the gap, never overwrites an existing row.
 * <p>
 * Seeds {@code endpoint}/{@code region}/{@code credentialRef} blank on
 * purpose: each adapter resolves blanks against {@code dms.scanning.*} at call
 * time, so the same row works whether dms-core runs on the host
 * ({@code localhost:3310}) or inside Compose ({@code clamav:3310}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanEngineBootstrap implements ApplicationRunner {

    private static final String DEFAULT_ENGINE_NAME = "platform-default";

    private final ScanEngineRepository scanEngineRepository;
    private final ScanProperties scanProperties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (scanEngineRepository.existsByTenantIdIsNullAndDeletedFalse()) {
            return;
        }

        ScanProvider provider = scanProperties.getDefaultProvider();
        ScanEngine saved = scanEngineRepository.save(ScanEngine.builder()
                .name(DEFAULT_ENGINE_NAME)
                .provider(provider)
                .scanMode(provider.scanMode())
                .timeoutSeconds(scanProperties.getTimeoutSeconds())
                .maxFileSizeBytes(scanProperties.getMaxFileSizeBytes())
                .status(ScanEngine.Status.active)
                .build());
        log.info("Created platform-default scan engine {} (provider={}, mode={})",
                saved.getScanEngineId(), saved.getProvider(), saved.getScanMode());
    }
}
