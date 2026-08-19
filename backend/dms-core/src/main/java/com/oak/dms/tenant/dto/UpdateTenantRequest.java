package com.oak.dms.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for updating an existing tenant's name, subscription tier,
 * and lifecycle status.
 */
public record UpdateTenantRequest(

        @NotBlank
        @Size(max = 255)
        String tenantName,

        @NotBlank
        String subscriptionTier,

        @NotNull
        Status status
) {
    public enum Status {active, suspended, archived}
}
