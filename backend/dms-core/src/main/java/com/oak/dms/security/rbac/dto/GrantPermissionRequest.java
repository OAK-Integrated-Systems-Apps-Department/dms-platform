package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for granting a permission from the catalog - optionally
 * scoped to a resource type such as "document" or "folder" - to a role.
 */
public record GrantPermissionRequest(

        @NotNull
        UUID permissionId,

        @Size(max = 100)
        String resourceScope
) {
}
