package com.oak.dms.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Changes to a tenant administrator's details by the platform administrator: name, email and username. The
 * username is optional - left out (or unchanged) it stays as it is. Sign-in status is not changed here (the
 * tenant admin account can't be deactivated).
 */
public record UpdateTenantAdminRequest(
        @NotBlank
        @Size(max = 255)
        String firstName,
        @Size(max = 255)
        String lastName,
        @NotBlank
        @Email
        @Size(max = 320)
        String email,
        @Size(min = 3, max = 100)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "username may only contain letters, numbers, dots, hyphens, and underscores")
        String username
) {
}
