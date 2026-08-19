package com.oak.dms.security.rbac.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a single role assignment held by a user,
 * including the resolved role name so clients don't need a second lookup.
 */
public record UserRoleResponse(
        UUID userRoleId,
        UUID tenantId,
        UUID userId,
        UUID roleId,
        String roleName,
        String scopeType,
        UUID scopeId,
        OffsetDateTime createdAt
) {
}
