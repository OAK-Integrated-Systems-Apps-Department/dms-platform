package com.oak.dms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Used for self-provisioning: an already-authenticated Keycloak principal
 * (valid JWT) calling to create their corresponding local Oak DMS profile
 * for the first time. tenantId and keycloakId are never taken from the
 * request body - they are read from the validated JWT claims only.
 */
public record ProvisionUserRequest(

        @NotBlank
        @Size(max = 255)
        String firstName,

        @Size(max = 255)
        String lastName
) {
}
