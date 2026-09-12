package com.oak.dms.user;

import com.oak.dms.user.dto.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * MapStruct entity/DTO mapping for {@link User}. `permissions` has no
 * corresponding entity field (it's resolved on demand from role
 * assignments via {@code AuthorizationService#myEffectivePermissionCodes()},
 * not stored on the user row), so it's ignored in the generated mapping
 * and the manual overload below is the only path that ever populates it -
 * see {@link UserResponse}'s own doc comment for why that's
 * `getCurrentUser()` only, matching the denormalized-field pattern already
 * used by {@code PermissionMapper}/{@code PermissionActionMapper}.
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "permissions", ignore = true)
    UserResponse toResponse(User user);

    default UserResponse toResponse(User user, List<String> permissions) {
        UserResponse base = toResponse(user);
        return new UserResponse(base.userId(), base.tenantId(), base.keycloakId(), base.email(),
                base.firstName(), base.lastName(), base.displayName(), base.mfaEnabled(), base.status(),
                base.verified(), base.verifiedAt(), base.createdAt(), base.updatedAt(), base.lastLoginAt(),
                permissions);
    }
}
