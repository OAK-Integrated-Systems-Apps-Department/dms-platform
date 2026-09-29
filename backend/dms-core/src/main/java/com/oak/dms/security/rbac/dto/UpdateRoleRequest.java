package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for renaming a role, changing its description, or
 * activating/deactivating it. The Edit Role screen always submits all
 * three fields together (there's no separate "just flip status" form), so
 * {@code status} is required here rather than optional — the same shape
 * {@code UpdateUserRequest} uses for {@code User.UserStatus}.
 */
public record UpdateRoleRequest(

        @NotBlank
        @Size(max = 100)
        String roleName,

        @Size(max = 500)
        String description,

        @NotNull
        Status status
) {
    public enum Status {active, inactive}
}
