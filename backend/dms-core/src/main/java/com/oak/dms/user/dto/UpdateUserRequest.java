package com.oak.dms.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for updating an existing user's name, email, MFA flag, and
 * status. Name and email changes are written to their Keycloak account too,
 * and status transitions to/from disabled toggle it - see
 * {@link com.oak.dms.user.UserServiceImpl#updateUser}.
 */
public record UpdateUserRequest(

        @NotBlank
        @Size(max = 255)
        String firstName,

        @Size(max = 255)
        String lastName,

        /** The new email; null leaves it as it is. Must not be another account's. */
        @Email(message = "Enter a valid email address")
        @Size(max = 255)
        String email,

        @NotNull
        Boolean mfaEnabled,

        @NotNull
        Status status
) {
    public enum Status {active, locked, disabled}

    /** Without an email - for callers that only change the name, MFA flag or status. */
    public UpdateUserRequest(String firstName, String lastName, Boolean mfaEnabled, Status status) {
        this(firstName, lastName, null, mfaEnabled, status);
    }
}
