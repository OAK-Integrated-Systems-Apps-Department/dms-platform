package com.oak.dms.security.rbac.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.security.rbac.PermissionAction}.
 * Includes the parent module's code/name alongside moduleId so a client
 * can render "MODULE / ACTION" without a second round trip, matching how
 * {@link RolePermissionResponse} denormalizes permissionCode.
 */
public record PermissionActionResponse(
        UUID actionId,
        UUID moduleId,
        String moduleCode,
        String actionCode,
        String actionName,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
