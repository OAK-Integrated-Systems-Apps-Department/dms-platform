package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for renaming a role or changing its description.
 */
public record UpdateRoleRequest(

        @NotBlank
        @Size(max = 100)
        String roleName,

        @Size(max = 500)
        String description
) {
}
