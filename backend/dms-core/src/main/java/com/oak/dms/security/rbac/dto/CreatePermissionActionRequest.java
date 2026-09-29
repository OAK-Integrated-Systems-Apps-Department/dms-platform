package com.oak.dms.security.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for adding a new action to a module (e.g. "VIEW" under
 * module "ROLE"). actionCode only needs to be unique within moduleId - see
 * {@code uq_permission_actions_module_action}.
 */
public record CreatePermissionActionRequest(

        @NotNull
        UUID moduleId,

        @NotBlank
        @Size(max = 50)
        String actionCode,

        @NotBlank
        @Size(max = 150)
        String actionName,

        @Size(max = 500)
        String description
) {
}
