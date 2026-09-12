package com.oak.dms.user.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Public API representation of a {@link com.oak.dms.user.User}.
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
 */
public record UserResponse(
        UUID userId,
        UUID tenantId,
        UUID keycloakId,
        String email,
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
        List<String> permissions
) {
}