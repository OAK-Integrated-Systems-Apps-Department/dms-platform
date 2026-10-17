package com.oak.dms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The caller changing their own password.
 * <p>
 * {@code currentPassword} is required and genuinely re-checked against
 * Keycloak before anything is written - it is not a formality. Keycloak's
 * admin API can set a password without knowing the old one, so without this
 * check a stolen session cookie would be enough to take the account over
 * permanently, locking out its real owner.
 *
 * @param totp a one-time code, required only for accounts with MFA enrolled -
 *             verifying the current password means a real token request, and
 *             Keycloak refuses that without the second factor
 */
public record ChangeOwnPasswordRequest(
        @NotBlank
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 256)
        String newPassword,

        String totp
) {
}
