package com.oak.dms.security.rbac.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a single permission grant on a role.
 */
public record RolePermissionResponse(
        UUID rolePermissionId,
        UUID tenantId,
        UUID roleId,
        UUID permissionId,
        String permissionCode,
        String resourceScope,
        OffsetDateTime createdAt
) {
}
