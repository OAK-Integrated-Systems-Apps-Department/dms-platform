package com.oak.dms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for updating an existing user's display name, MFA flag, and
 * status. Status transitions to/from disabled also toggle the account in
 * Keycloak - see {@link com.oak.dms.user.UserServiceImpl#updateUser}.
 */
public record UpdateUserRequest(

        @NotBlank
        @Size(max = 255)
        String displayName,

        @NotNull
        Boolean mfaEnabled,

        @NotNull
        Status status
) {
    public enum Status {active, locked, disabled}
}
