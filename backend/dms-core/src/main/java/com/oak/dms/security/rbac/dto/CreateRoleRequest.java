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
        String description,

        /**
         * Optional — new roles default to {@code active} when omitted (see
         * RoleServiceImpl#createRole). Accepted here mainly so the Add Role
         * screen's status toggle, which is visible even when creating a
         * role, can be honored if someone deliberately creates a role
         * already inactive.
         */
        Status status
) {
    public enum Status {active, inactive}
}
