package com.oak.dms.security.auth;

import java.util.UUID;

/**
 * Request-scoped holder for the caller's authenticated tenant and user
 * identity, populated by {@link TenantContextFilter} from validated JWT
 * claims. Every service method that touches tenant-scoped data reads from
 * here rather than trusting a tenantId supplied in the request body/path,
 * so one tenant can never operate on another tenant's rows.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CURRENT_USER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId, UUID userId) {
        CURRENT_TENANT.set(tenantId);
        CURRENT_USER.set(userId);
    }

    public static UUID getTenantId() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context bound to the current request");
        }
        return tenantId;
    }

    public static UUID getUserId() {
        UUID userId = CURRENT_USER.get();
        if (userId == null) {
            throw new IllegalStateException("No user context bound to the current request");
        }
        return userId;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_USER.remove();
    }
}
