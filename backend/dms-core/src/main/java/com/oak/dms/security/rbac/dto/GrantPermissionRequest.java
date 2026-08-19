package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for granting a permission code - optionally scoped to a
 * resource type such as "document" or "folder" - to a role.
 */
public record GrantPermissionRequest(

        @NotBlank
        @Size(max = 100)
        String permissionCode,

        @Size(max = 100)
        String resourceScope
) {
}
