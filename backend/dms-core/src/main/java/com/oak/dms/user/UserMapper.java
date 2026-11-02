package com.oak.dms.user;

import com.oak.dms.user.dto.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * MapStruct entity/DTO mapping for {@link User}. Neither `tenantName` nor
 * `permissions` has a corresponding entity field - `tenantName` is
 * resolved from {@code TenantRepository} by the caller (see
 * {@link UserResponse}'s own doc comment: {@code User} only carries the
 * bare {@code tenantId}, no JPA relation to join through), and
 * `permissions` is resolved on demand from role assignments via
 * {@code AuthorizationService#myEffectivePermissionCodes()}. Both are
 * ignored in the generated mapping; the manual overloads below are the
 * only paths that ever populate them, matching the denormalized-field
 * pattern already used by {@code PermissionMapper}/
 * {@code PermissionActionMapper}.
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "tenantName", ignore = true)
    @Mapping(target = "permissions", ignore = true)
    @Mapping(target = "roles", ignore = true)
    @Mapping(target = "impersonating", ignore = true)
    @Mapping(target = "impersonatorDisplayName", ignore = true)
    UserResponse toResponse(User user);

    /** Every caller resolves and passes `tenantName` - see {@link UserResponse}'s doc comment. */
    default UserResponse toResponse(User user, String tenantName) {
        return toResponse(user, tenantName, null, null, null, null);
    }

    /**
     * `permissions`/`roles`/impersonation status/`departmentName` populated in addition to `tenantName` -
     * `getCurrentUser()`/`updateCurrentUser()` only. `impersonatorDisplayName` non-null implies
     * impersonating; null means not impersonating, regardless of `permissions`.
     */
    default UserResponse toResponse(User user, String tenantName, List<String> permissions,
            List<String> roles, String impersonatorDisplayName, String departmentName) {
        UserResponse base = toResponse(user);
        return new UserResponse(base.userId(), base.tenantId(), tenantName, base.keycloakId(), base.email(),
                base.username(), base.firstName(), base.lastName(), base.displayName(), base.mfaEnabled(),
                base.status(), base.verified(), base.verifiedAt(), base.createdAt(), base.updatedAt(),
                base.lastLoginAt(), base.lastPasswordChangedAt(), permissions, roles,
                impersonatorDisplayName != null, impersonatorDisplayName, base.departmentId(), base.position(),
                departmentName);
    }
}
