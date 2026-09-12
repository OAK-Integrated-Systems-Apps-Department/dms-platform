package com.oak.dms.security.auth.dto;

import com.oak.dms.security.auth.PasswordResetToken;

/**
 * Response for {@code GET /api/v1/auth/reset-password/validate}, letting
 * {@code /update-password} show "this link is invalid or has expired"
 * immediately on page load instead of only after the person fills in a
 * new password and submits.
 * <p>
 * {@code kind} is null whenever {@code valid} is false (an unknown,
 * already-used, or expired token) and otherwise tells the frontend which
 * of the two set-password interfaces to render - {@code NEW_USER}
 * ("welcome, set your password") or {@code PASSWORD_RESET} ("reset your
 * password", covering both the self-service and admin-triggered flows).
 */
public record ValidateResetTokenResponse(boolean valid, PasswordResetToken.TokenKind kind) {
}
