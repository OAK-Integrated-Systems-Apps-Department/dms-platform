package com.oak.dms.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for provisioning a new tenant. domain is validated as a
 * lowercase hostname since it doubles as the tenant's login domain.
 */
public record CreateTenantRequest(

        @NotBlank
        @Size(max = 255)
        String tenantName,

        @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$",
                message = "domain must be a valid lowercase hostname, e.g. acme.oakdms.com")
        String domain,

        @NotBlank
        String subscriptionTier
) {
}
