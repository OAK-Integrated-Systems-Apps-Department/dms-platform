package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for adding a new module to the platform-wide permission
 * catalog (e.g. "ROLE", "DOCUMENT").
 */
public record CreatePermissionModuleRequest(

        @NotBlank
        @Size(max = 50)
        String moduleCode,

        @NotBlank
        @Size(max = 150)
        String moduleName,

        @Size(max = 500)
        String description
) {
}
