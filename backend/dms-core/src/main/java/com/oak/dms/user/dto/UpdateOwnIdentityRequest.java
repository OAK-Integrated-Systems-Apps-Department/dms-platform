package com.oak.dms.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The caller changing their own name and email, applied to Keycloak (which
 * owns the identity) and mirrored onto the local user row when one exists.
 * <p>
 * Deliberately narrower than {@code UpdateUserRequest}, the admin-facing
 * equivalent: no tenant, no role assignments, no status, no MFA flag - so
 * this can never be used to escalate one's own account.
 */
public record UpdateOwnIdentityRequest(
        @NotBlank
        @Size(max = 255)
        String firstName,

        @Size(max = 255)
        String lastName,

        @NotBlank
        @Email
        @Size(max = 320)
        String email
) {
}
