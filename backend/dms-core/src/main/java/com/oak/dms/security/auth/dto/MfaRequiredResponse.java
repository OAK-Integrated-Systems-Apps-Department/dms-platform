package com.oak.dms.security.auth.dto;

/**
 * Response for {@code GET /api/v1/auth/mfa-required}. Deliberately a single
 * boolean with no other detail: an unknown username and a username with no
 * OTP credential configured both resolve to {@code mfaRequired=false} (see
 * {@code AuthService#isMfaRequired}), so this endpoint can never be used to
 * enumerate which usernames exist in the system.
 */
public record MfaRequiredResponse(boolean mfaRequired) {
}
