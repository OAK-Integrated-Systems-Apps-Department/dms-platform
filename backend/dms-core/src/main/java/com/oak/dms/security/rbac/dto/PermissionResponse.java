package com.oak.dms.security.rbac.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.security.rbac.Permission}.
 * actionCode/moduleId/moduleCode are denormalized from the related
 * {@link com.oak.dms.security.rbac.PermissionAction} and
 * {@link com.oak.dms.security.rbac.PermissionModule} rows so a client can
 * render "MODULE / ACTION" (replacing the old free-text `category` field)
 * without a second round trip - see {@link com.oak.dms.security.rbac.PermissionMapper}.
 */
public record PermissionResponse(
        UUID permissionId,
        String permissionCode,
        String name,
        String description,
        UUID actionId,
        String actionCode,
        UUID moduleId,
        String moduleCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
