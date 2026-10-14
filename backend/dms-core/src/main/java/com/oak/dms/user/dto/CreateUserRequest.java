package com.oak.dms.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Used by a tenant administrator to invite a new user. Creates the
 * account in Keycloak (via KeycloakUserService) and the corresponding
 * local profile row in the same transaction-equivalent flow.
 */
public record CreateUserRequest(

        @NotBlank
        @Email(message = "Enter a valid email address")
        // Keycloak keeps at most 255 characters of an email (user_entity.email) - longer failed there.
        @Size(max = 255, message = "An email can be at most 255 characters")
        String email,

        /**
         * The account's real login id, distinct from {@code email} - see
         * {@link com.oak.dms.user.User#getUsername()}'s own doc comment.
         * Same character set Keycloak itself accepts for a username.
         */
        @NotBlank
        @Size(min = 3, max = 100)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "username may only contain letters, numbers, dots, hyphens, and underscores")
        String username,

        @NotBlank
        @Size(max = 255)
        String firstName,

        @Size(max = 255)
        String lastName
) {
}
