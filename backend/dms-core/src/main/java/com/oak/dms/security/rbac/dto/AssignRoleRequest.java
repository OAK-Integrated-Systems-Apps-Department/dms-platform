package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for assigning a role to a user. scopeId is required unless
 * scopeType is TENANT; see {@link com.oak.dms.security.rbac.UserRole} for
 * why it is not a foreign key.
 */
public record AssignRoleRequest(

        @NotNull
        UUID roleId,

        @NotNull
        ScopeType scopeType,

        /** Required unless scopeType is TENANT. */
        UUID scopeId
) {
    public enum ScopeType {tenant, department, folder, document}
}
