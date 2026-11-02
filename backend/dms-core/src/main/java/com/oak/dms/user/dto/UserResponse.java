package com.oak.dms.user.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.user.User}.
 * <p>
 * {@code tenantName} is resolved from {@link com.oak.dms.tenant.Tenant} by
 * {@code tenantId} - {@code User} itself only carries the bare
 * {@code tenantId} column (no JPA relation), so every {@code UserMapper}
 * caller in {@code UserServiceImpl} looks it up via
 * {@code TenantRepository} and passes it in; {@code listUsers} batches
 * this across the page instead of resolving it per row. {@code null} only
 * if the tenant row is somehow missing/deleted, which shouldn't happen for
 * a user's own tenant in practice.
 * <p>
 * {@code permissions} is the caller's own resolved permission-code set
 * (their role assignments' grants, flattened and deduplicated - see
 * {@link com.oak.dms.security.auth.AuthorizationService#myEffectivePermissionCodes()}),
 * and is only ever populated on {@code GET /api/v1/users/me} - see
 * {@link com.oak.dms.user.UserMapper}'s manual overload. It's {@code null}
 * on every other endpoint that returns this same DTO (create/get/list/
 * update/reactivate an arbitrary user), both because computing another
 * user's effective permissions isn't needed there and because doing so
 * for every row of a paginated user list would mean N extra queries per
 * page. The frontend's {@code usePermissions()} hook is the only reader
 * of this field.
 * <p>
 * {@code roles} is the same {@code GET /api/v1/users/me}-only story: the
 * caller's role names for display (platform roles, then applicable tenant
 * roles - see
 * {@link com.oak.dms.security.auth.AuthorizationService#myRoleNames()}),
 * {@code null} everywhere else. Never an access-control input.
 * <p>
 * {@code impersonating}/{@code impersonatorDisplayName} are the same
 * {@code GET /api/v1/users/me}-only story as {@code permissions} above -
 * {@code null}/{@code false} everywhere else. Sourced from
 * {@link com.oak.dms.security.auth.TenantContext#getImpersonatorIdOrNull()},
 * never client-supplied: this is what the frontend's impersonation banner
 * and "Return to Admin" action key off, so it must be exactly as trustworthy
 * as {@code impersonated_by} on an audit event.
 */
public record UserResponse(
        UUID userId,
        UUID tenantId,
        String tenantName,
        UUID keycloakId,
        String email,
        String username,
        String firstName,
        String lastName,
        String displayName,
        boolean mfaEnabled,
        String status,
        boolean verified,
        OffsetDateTime verifiedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime lastLoginAt,
        /** See {@link com.oak.dms.user.User#getLastPasswordChangedAt()}'s doc comment. */
        OffsetDateTime lastPasswordChangedAt,
        List<String> permissions,
        List<String> roles,
        boolean impersonating,
        String impersonatorDisplayName,
        /** Primary department (V43), or null. Changed via PUT /api/v1/users/{id}/department. */
        UUID departmentId,
        /** Job title (V117), or null. Self-set via PUT /api/v1/users/me. */
        String position,
        /**
         * {@code departmentId}'s resolved name - the same {@code GET /api/v1/users/me}-only story as
         * {@code permissions}/{@code roles} above (null on every other endpoint that returns this DTO).
         * Resolving a user's own department name isn't otherwise self-service: {@code GET
         * /api/v1/departments} and {@code GET /api/v1/users/{userId}/department} are both admin/template-
         * designer gated, so a caller who only holds ordinary signing/approval permissions has no other
         * way to learn their own department's name client-side (needed to preview the Name/Position/
         * Department/Date block {@code SignatureStampComposer} stamps onto a signed document).
         */
        String departmentName
) {
}