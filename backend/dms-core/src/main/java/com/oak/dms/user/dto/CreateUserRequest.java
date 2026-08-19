package com.oak.dms.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Used by a tenant administrator to invite a new user. Creates the
 * account in Keycloak (via KeycloakUserService) and the corresponding
 * local profile row in the same transaction-equivalent flow.
 */
public record CreateUserRequest(

        @NotBlank
        @Email
        @Size(max = 320)
        String email,

        @NotBlank
        @Size(max = 255)
        String displayName
) {
}
