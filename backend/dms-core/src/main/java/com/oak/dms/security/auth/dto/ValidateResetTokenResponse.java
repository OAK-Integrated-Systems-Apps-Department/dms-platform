package com.oak.dms.security.auth.dto;

/**
 * Response for {@code GET /api/v1/auth/reset-password/validate}, letting
 * {@code /update-password} show "this link is invalid or has expired"
 * immediately on page load instead of only after the person fills in a
 * new password and submits.
 */
public record ValidateResetTokenResponse(boolean valid) {
}
