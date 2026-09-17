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
    private static final ThreadLocal<String> CURRENT_USER_DISPLAY_NAME = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId, UUID userId) {
        set(tenantId, userId, null);
    }

    /**
     * displayName is a snapshot for the current request only (e.g. for
     * AuditService's actor_display_name) - it is never itself the source
     * of truth for a user's name.
     */
    public static void set(UUID tenantId, UUID userId, String displayName) {
        CURRENT_TENANT.set(tenantId);
        CURRENT_USER.set(userId);
        CURRENT_USER_DISPLAY_NAME.set(displayName);
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

    /** Null if unset, or if the current user simply has no display name recorded - never throws. */
    public static String getDisplayNameOrNull() {
        return CURRENT_USER_DISPLAY_NAME.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_USER.remove();
        CURRENT_USER_DISPLAY_NAME.remove();
    }
}
