package com.oak.dms.storage.dto;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Never includes {@code credentialRef} or any credential value - see {@link StorageLocation}'s own doc comment. */
public record StorageLocationResponse(
        UUID storageLocationId,
        UUID tenantId,
        String name,
        StorageProvider provider,
        String endpoint,
        String region,
        String bucket,
        String basePath,
        boolean pathStyleAccess,
        StorageLocation.Status status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
