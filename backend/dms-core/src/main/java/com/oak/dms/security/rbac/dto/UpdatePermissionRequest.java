package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for editing a permission's descriptive metadata.
 * permissionCode is intentionally not editable here - it's the string
 * matched against {@code @authz.hasPermission(...)} checks throughout the
 * codebase and referenced by existing role_permissions grants, so changing
 * it would silently break every check and grant that expects the old value.
 * Retire the permission and create a new one instead if the code itself
 * needs to change. actionId (regrouping the permission under a different
 * module/action) IS editable, and must reference an existing, non-deleted
 * {@link com.oak.dms.security.rbac.entity.PermissionAction}.
 */
public record UpdatePermissionRequest(

        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 500)
        String description,

        @NotNull
        UUID actionId
) {
}
