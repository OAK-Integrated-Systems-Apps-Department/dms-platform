package com.oak.dms.scanning.dto;

import com.oak.dms.storage.entity.StorageLocation;

import java.util.UUID;

/**
 * What a {@link com.oak.dms.scanning.service.ScanService} evaluates: always a
 * staged object (a storage location plus key), never a raw stream or a
 * provider SDK type - that is what lets one {@code scan} signature serve both
 * a synchronous stream scan (ClamAV reads the object and streams it to
 * {@code clamd}) and an asynchronous object scan (the cloud scanners already
 * scanned it on write; the adapter reads the provider's verdict).
 *
 * @param expectedETag     the staged object's ETag at staging time; cloud verdicts for any other bytes are ignored. Null for FILE_SYSTEM staging.
 * @param hintedThreatName a threat name a cloud callback reported - used only if the provider's own tag independently confirms infection.
 */
public record ScanTarget(
        UUID scanResultId,
        StorageLocation stagingLocation,
        String stagingObjectKey,
        long sizeBytes,
        String mimeType,
        String expectedETag,
        String hintedThreatName
) {
}
