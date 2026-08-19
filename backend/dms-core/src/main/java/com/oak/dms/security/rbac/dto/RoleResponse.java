package com.oak.dms.security.rbac.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.security.rbac.Role}.
 */
public record RoleResponse(
        UUID roleId,
        UUID tenantId,
        String roleName,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
