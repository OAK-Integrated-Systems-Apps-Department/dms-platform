package com.oak.dms.security.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/reset-password}. {@code newPassword}'s
 * own strength (length/complexity beyond this basic floor) is enforced by
 * Keycloak's realm password policy when {@link com.oak.dms.security.keycloak.KeycloakUserService#setPassword}
 * calls the Admin API - not duplicated here.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "Reset token is required")
        @Size(max = 512, message = "Reset token is too long")
        String token,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 512, message = "Password must be at least 8 characters")
        String newPassword
) {
}
