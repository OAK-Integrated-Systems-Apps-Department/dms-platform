package com.oak.dms.security.rbac.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.security.rbac.Role}.
 *
 * {@code status} is {@code "active"} or {@code "inactive"} (mirrors
 * {@code UserResponse.status}'s lower-case convention). Note this DTO does
 * not carry permissions or assigned-user counts — those are exposed
 * separately via the role-permission and user-role endpoints, and the
 * frontend's Role Management screens currently keep both client-side
 * until that wiring lands.
 */
public record RoleResponse(
        UUID roleId,
        UUID tenantId,
        String roleName,
        String description,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
