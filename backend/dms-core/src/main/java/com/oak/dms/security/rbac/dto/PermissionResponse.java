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
 * <p>
 * {@code shared} is {@code true} for the platform's default catalog
 * (visible to and usable by every tenant) and {@code false} for a
 * permission a tenant created themselves (visible only to that tenant -
 * a caller only ever sees their own, so if this is false it's always
 * theirs). It's a derived convenience (`tenantId == null`) rather than
 * making the client compare `tenantId` against its own, since the client
 * has no reliable way to know "my own tenant id" to compare against in
 * every context this response is used from. Only permissions where
 * {@code shared} is false can be edited or deleted - see
 * {@link com.oak.dms.security.rbac.PermissionServiceImpl#updatePermission}.
 */
public record PermissionResponse(
        UUID permissionId,
        String permissionCode,
        String name,
        String description,
        boolean shared,
        UUID actionId,
        String actionCode,
        UUID moduleId,
        String moduleCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
