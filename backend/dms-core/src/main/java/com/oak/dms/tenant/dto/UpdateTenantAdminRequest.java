package com.oak.dms.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Changes to a tenant administrator's details by the platform administrator. The username is fixed
 * at provisioning; sign-in status is not changed here (the tenant admin account can't be deactivated).
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
        String email
) {
}
