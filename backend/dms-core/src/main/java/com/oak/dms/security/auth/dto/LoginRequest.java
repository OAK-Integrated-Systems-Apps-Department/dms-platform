package com.oak.dms.security.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login}. Username/password are bounded in
 * length before they ever reach Keycloak - not because Keycloak can't
 * handle a large payload, but so a malicious or misbehaving client can't
 * use this public, unauthenticated endpoint to push large strings through
 * the app for free. {@code totp} is optional (omitted on the initial
 * credentials submission) and, when present, is constrained to exactly
 * what a TOTP/HOTP code from an authenticator app looks like - this is a
 * cheap, deterministic rejection of garbage input, not a security
 * boundary; Keycloak is still the sole authority on whether a code is
 * actually correct.
 */
public record LoginRequest(
        @NotBlank(message = "Username is required")
        @Size(max = 255, message = "Username is too long")
        String username,

        @NotBlank(message = "Password is required")
        @Size(max = 512, message = "Password is too long")
        String password,

        @Pattern(regexp = "^[0-9]{6,8}$", message = "Verification code must be 6-8 digits")
        String totp,

        boolean rememberMe
) {
}
