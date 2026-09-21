package com.oak.dms.security.rbac.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.security.rbac.entity.PermissionModule}.
 */
public record PermissionModuleResponse(
        UUID moduleId,
        String moduleCode,
        String moduleName,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
