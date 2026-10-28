package com.oak.dms.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for onboarding a brand-new client in one call: the tenant
 * itself plus their first administrator, who is created directly with the
 * TENANT_ADMIN realm role already granted. See
 * {@link com.oak.dms.tenant.TenantProvisioningService} for why this exists
 * as a single atomic operation rather than composing
 * {@code POST /api/v1/tenants} with a separate user-creation call: there is
 * no such separate call available for this, because
 * {@code POST /api/v1/users} always creates the user in the caller's own
 * tenant, never an arbitrary target tenant, and grants no role.
 */
public record ProvisionTenantRequest(

        @NotBlank
        @Size(max = 255)
        String tenantName,

        @NotBlank
        @Size(max = 255)
        // Unambiguous and counted rather than unbounded - see the note on
        // the same field in CreateTenantRequest for why it is not the
        // obvious hostname regex.
        @Pattern(regexp = "^[a-z0-9]+(-+[a-z0-9]+){0,127}(\\.[a-z0-9]+(-+[a-z0-9]+){0,127}){0,127}$",
                message = "domain must be a valid lowercase hostname, e.g. acme.oakdms.com")
        String domain,

        @NotBlank
        String subscriptionTier,

        @NotBlank
        @Email
        @Size(max = 320)
        String adminEmail,

        /** See {@link com.oak.dms.user.dto.CreateUserRequest#username()}'s own doc comment. */
        @NotBlank
        @Size(min = 3, max = 100)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "adminUsername may only contain letters, numbers, dots, hyphens, and underscores")
        String adminUsername,

        @NotBlank
        @Size(max = 255)
        String adminFirstName,

        @Size(max = 255)
        String adminLastName
) {
}
