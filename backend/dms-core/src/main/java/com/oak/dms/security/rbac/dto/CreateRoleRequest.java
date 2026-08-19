package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for defining a new role within the caller's tenant.
 */
public record CreateRoleRequest(

        @NotBlank
        @Size(max = 100)
        String roleName,

        @Size(max = 500)
        String description
) {
}
