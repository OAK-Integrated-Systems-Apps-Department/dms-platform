package com.oak.dms.tenant.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One administrator of a tenant, as the platform administrator sees them in the organisation list:
 * who they are, whether they have finished setting up their account, and when they last signed in.
 */
public record TenantAdminResponse(
        UUID userId,
        String username,
        String email,
        String firstName,
        String lastName,
        String displayName,
        /** active, locked or disabled. */
        String status,
        /** The administrator has set their own password (accepted the invitation). */
        boolean verified,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt
) {
}
