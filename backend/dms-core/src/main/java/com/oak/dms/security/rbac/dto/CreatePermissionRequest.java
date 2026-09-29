package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for adding a new permission to the platform-wide catalog.
 * actionId must reference an existing, non-deleted
 * {@link com.oak.dms.security.rbac.entity.PermissionAction} - see
 * {@link com.oak.dms.security.rbac.service.impl.PermissionServiceImpl#createPermission}.
 */
public record CreatePermissionRequest(

        @NotBlank
        @Size(max = 100)
        String permissionCode,

        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 500)
        String description,

        @NotNull
        UUID actionId
) {
}
