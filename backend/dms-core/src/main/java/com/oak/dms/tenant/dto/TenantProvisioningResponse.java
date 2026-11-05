package com.oak.dms.tenant.dto;

import com.oak.dms.user.dto.UserResponse;

/**
 * Result of {@code POST /api/v1/tenants/provision}: the newly created
 * tenant and its first administrator (already granted TENANT_ADMIN and
 * able to log in once they set their real password via the required-actions
 * email sent during provisioning).
 */
public record TenantProvisioningResponse(
        TenantResponse tenant,
        UserResponse admin,
        /** The other administrators created with the organisation, in the order requested (empty if none). */
        java.util.List<UserResponse> additionalAdmins
) {
}
