package com.oak.dms.security.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/forgot-password}. */
public record ForgotPasswordRequest(
        @NotBlank(message = "Username or email is required")
        @Size(max = 320, message = "Username or email is too long")
        String usernameOrEmail
) {
}
