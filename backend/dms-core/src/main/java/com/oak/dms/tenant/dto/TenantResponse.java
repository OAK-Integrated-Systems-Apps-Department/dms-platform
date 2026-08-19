package com.oak.dms.tenant.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.tenant.Tenant}.
 */
public record TenantResponse(
        UUID tenantId,
        String tenantName,
        String domain,
        String subscriptionTier,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
