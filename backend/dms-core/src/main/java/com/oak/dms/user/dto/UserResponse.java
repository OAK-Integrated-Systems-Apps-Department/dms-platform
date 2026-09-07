package com.oak.dms.user.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.user.User}.
 */
public record UserResponse(
        UUID userId,
        UUID tenantId,
        UUID keycloakId,
        String email,
        String firstName,
        String lastName,
        String displayName,
        boolean mfaEnabled,
        String status,
        boolean verified,
        OffsetDateTime verifiedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}