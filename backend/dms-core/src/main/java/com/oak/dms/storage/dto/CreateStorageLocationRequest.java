package com.oak.dms.storage.dto;

import com.oak.dms.storage.enums.StorageProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Registers a new {@code StorageLocation} for the caller's tenant.
 * Deliberately has no access-key/secret-key fields - see
 * {@code credentialRef}'s own doc comment on {@code StorageLocation}: a
 * tenant's S3 credentials are supplied out-of-band as environment
 * variables keyed by {@code credentialRef}, never through this API.
 */
public record CreateStorageLocationRequest(
        @NotBlank String name,
        @NotNull StorageProvider provider,
        String endpoint,
        String region,
        String bucket,
        String basePath,
        boolean pathStyleAccess,
        String credentialRef
) {
}
