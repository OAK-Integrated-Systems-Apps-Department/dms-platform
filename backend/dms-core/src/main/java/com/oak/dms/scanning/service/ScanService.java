package com.oak.dms.scanning.service;

import com.oak.dms.scanning.dto.ScanTarget;
import com.oak.dms.scanning.dto.ScanVerdict;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.exception.ScanException;
import com.oak.dms.storage.enums.StorageProvider;

import java.util.Optional;

/**
 * The one scanning abstraction the ingestion gate depends on - the scanning
 * counterpart to {@link com.oak.dms.storage.service.ObjectStorageService}.
 * Never exposes a clamd socket, an Azure type or an AWS SDK type to a
 * caller; every failure surfaces as a {@link ScanException} subclass.
 * <p>
 * One signature serves both scan modes because the target is always a
 * <em>staged object</em>:
 * <ul>
 *   <li>{@link ScanMode#pre_store_stream} (ClamAV) - {@link #scan} reads the
 *   staged object, streams it to the engine and returns its verdict now.</li>
 *   <li>{@link ScanMode#post_store_object} (Defender, GuardDuty) - the provider
 *   already scans the object when it is written to the protected staging
 *   container/bucket; {@link #scan} reads the provider's verdict and returns
 *   {@code PENDING} until there is one.</li>
 * </ul>
 * {@link #scan} is idempotent and safe to call repeatedly, so retries,
 * polling, callbacks and crash recovery all use the same call.
 * <p>
 * Three beans implement this; {@link ScanEngineFactory} picks one per
 * {@code engine.getProvider()} - callers go through the factory, never inject
 * an adapter directly.
 */
public interface ScanService {

    ScanProvider supportedProvider();

    default ScanMode scanMode() {
        return supportedProvider().scanMode();
    }

    /** The storage provider the staged object must live on for this engine to see it; empty means any (ClamAV). */
    Optional<StorageProvider> requiredStagingProvider();

    /** @throws ScanException on any engine failure - never returns a verdict it doesn't have. */
    ScanVerdict scan(ScanEngine engine, ScanTarget target);
}
