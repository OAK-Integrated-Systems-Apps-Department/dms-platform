package com.oak.dms.security.auth;

import java.util.List;
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
    private static final ThreadLocal<UUID> CURRENT_USER_KEYCLOAK_ID = new ThreadLocal<>();
    /**
     * Set only by {@code ImpersonationContextFilter}, after this context has
     * already been rebound from the real admin's identity to the
     * impersonation target's - the one place in the whole request that
     * still knows who the real, authenticated caller was. {@code null} for
     * every ordinary, non-impersonated request. {@link com.oak.dms.audit.service.AuditService}
     * is the only reader: every audit event's {@code impersonated_by}/
     * {@code impersonated_by_display_name} columns come from here, never
     * from a call site (see {@code AuditEventBuilder}, which has no setter
     * for either on purpose).
     */
    private static final ThreadLocal<UUID> CURRENT_IMPERSONATOR_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_IMPERSONATOR_DISPLAY_NAME = new ThreadLocal<>();
    /**
     * Per-REQUEST memoization of the impersonation target's live Keycloak
     * platform roles (see {@link AuthorizationService#hasFullAccess()}) -
     * {@code null} means "not resolved yet this request", an actual (maybe
     * empty) list means "resolved". Exists purely to collapse what would
     * otherwise be a separate live Keycloak Admin API call for every one of
     * potentially several permission checks a single request makes (a
     * controller method can easily call {@code hasPermission}/
     * {@code isTenantAdmin} more than once) down to at most one per request -
     * NOT a cross-request cache, so a permission change still takes effect
     * on the very next request exactly as intended. Cleared alongside
     * everything else in {@link #clear()}, so it can never leak into a
     * later, unrelated request on a pooled thread.
     */
    private static final ThreadLocal<List<String>> CURRENT_TARGET_PLATFORM_ROLES_CACHE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId, UUID userId) {
        set(tenantId, userId, null, null);
    }

    /**
     * displayName/keycloakId are snapshots for the current request only
     * (e.g. for AuditService's actor_display_name, or for looking up the
     * impersonation target's platform roles without a second DB round trip)
     * - neither is itself the source of truth; both are re-read from the
     * users table on every request by whichever filter calls this.
     */
    public static void set(UUID tenantId, UUID userId, String displayName, UUID keycloakId) {
        CURRENT_TENANT.set(tenantId);
        CURRENT_USER.set(userId);
        CURRENT_USER_DISPLAY_NAME.set(displayName);
        CURRENT_USER_KEYCLOAK_ID.set(keycloakId);
        // A fresh identity binding invalidates any platform-roles lookup
        // memoized against whichever identity was bound before it -
        // relevant when ImpersonationContextFilter calls this a second time
        // (real admin -> target) within the same request/thread.
        CURRENT_TARGET_PLATFORM_ROLES_CACHE.remove();
    }

    /** Called only by {@code ImpersonationContextFilter}, once per impersonated request, after re-binding the fields above to the target. */
    public static void setImpersonator(UUID impersonatorId, String impersonatorDisplayName) {
        CURRENT_IMPERSONATOR_ID.set(impersonatorId);
        CURRENT_IMPERSONATOR_DISPLAY_NAME.set(impersonatorDisplayName);
    }

    /** Null for every ordinary request - non-null only for the duration of a request made under an active impersonation session. */
    public static UUID getImpersonatorIdOrNull() {
        return CURRENT_IMPERSONATOR_ID.get();
    }

    /** The real admin's display name while impersonating; null otherwise - see {@link #getImpersonatorIdOrNull()}. */
    public static String getImpersonatorDisplayNameOrNull() {
        return CURRENT_IMPERSONATOR_DISPLAY_NAME.get();
    }

    /** Null if unset - e.g. context bound via the 2-arg {@link #set(UUID, UUID)} overload, which no production call site uses today. */
    public static UUID getKeycloakIdOrNull() {
        return CURRENT_USER_KEYCLOAK_ID.get();
    }

    /** The bound tenant, or null outside a request/job (callers that work either way). */
    public static UUID getTenantIdOrNull() {
        return CURRENT_TENANT.get();
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

    /** See {@link #CURRENT_TARGET_PLATFORM_ROLES_CACHE}'s own doc comment - null means "not resolved yet this request". */
    public static List<String> getCachedTargetPlatformRolesOrNull() {
        return CURRENT_TARGET_PLATFORM_ROLES_CACHE.get();
    }

    public static void cacheTargetPlatformRoles(List<String> roles) {
        CURRENT_TARGET_PLATFORM_ROLES_CACHE.set(roles);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_USER.remove();
        CURRENT_USER_DISPLAY_NAME.remove();
        CURRENT_USER_KEYCLOAK_ID.remove();
        CURRENT_IMPERSONATOR_ID.remove();
        CURRENT_IMPERSONATOR_DISPLAY_NAME.remove();
        CURRENT_TARGET_PLATFORM_ROLES_CACHE.remove();
    }
}
