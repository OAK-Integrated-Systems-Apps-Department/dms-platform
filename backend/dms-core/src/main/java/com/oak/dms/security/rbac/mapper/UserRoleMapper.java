package com.oak.dms.security.rbac.mapper;

import com.oak.dms.security.rbac.entity.UserRole;

import com.oak.dms.security.rbac.dto.UserRoleResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct entity/DTO mapping for {@link UserRole}. roleName has no
 * corresponding entity field (it lives on the related Role row), so the
 * generated mapping is extended with a manual overload that accepts it
 * as a separate parameter - see the two toResponse() methods below.
 */
@Mapper(componentModel = "spring")
public interface UserRoleMapper {

    @Mapping(target = "roleName", ignore = true)
    UserRoleResponse toResponse(UserRole userRole);

    default UserRoleResponse toResponse(UserRole userRole, String roleName) {
        UserRoleResponse base = toResponse(userRole);
        return new UserRoleResponse(base.userRoleId(), base.tenantId(), base.userId(), base.roleId(),
                roleName, base.scopeType(), base.scopeId(), base.createdAt());
    }
}
