package com.oak.dms.security.rbac.mapper;

import com.oak.dms.security.rbac.entity.RolePermission;

import com.oak.dms.security.rbac.dto.RolePermissionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct entity/DTO mapping for {@link RolePermission}. permissionCode
 * has no corresponding entity field (it lives on the related Permission
 * row), so the generated mapping is extended with a manual overload that
 * accepts it as a separate parameter - see the two toResponse() methods
 * below, matching {@link UserRoleMapper}'s pattern for roleName.
 */
@Mapper(componentModel = "spring")
public interface RolePermissionMapper {

    @Mapping(target = "permissionCode", ignore = true)
    RolePermissionResponse toResponse(RolePermission rolePermission);

    default RolePermissionResponse toResponse(RolePermission rolePermission, String permissionCode) {
        RolePermissionResponse base = toResponse(rolePermission);
        return new RolePermissionResponse(base.rolePermissionId(), base.tenantId(), base.roleId(),
                base.permissionId(), permissionCode, base.resourceScope(), base.createdAt());
    }
}
