package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for editing a module's descriptive metadata. moduleCode is
 * intentionally not editable here - it's matched against the module prefix
 * of every {@code permission_code} in the catalog (e.g. "role.view" ->
 * "ROLE"), so changing it would silently orphan every action and
 * permission derived from the old code. Retire the module and create a
 * new one instead if the code itself needs to change.
 */
public record UpdatePermissionModuleRequest(

        @NotBlank
        @Size(max = 150)
        String moduleName,

        @Size(max = 500)
        String description
) {
}
