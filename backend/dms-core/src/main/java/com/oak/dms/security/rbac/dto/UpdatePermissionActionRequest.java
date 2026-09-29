package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for editing an action's descriptive metadata. actionCode
 * and moduleId are intentionally not editable here, for the same reason
 * {@link UpdatePermissionModuleRequest} keeps moduleCode fixed - retire
 * the action and create a new one under the correct module instead.
 */
public record UpdatePermissionActionRequest(

        @NotBlank
        @Size(max = 150)
        String actionName,

        @Size(max = 500)
        String description
) {
}
