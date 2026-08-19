package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.RolePermissionResponse;
import com.oak.dms.security.rbac.RolePermission;
import org.mapstruct.Mapper;

/**
 * MapStruct entity/DTO mapping for {@link RolePermission}.
 */
@Mapper(componentModel = "spring")
public interface RolePermissionMapper {
    RolePermissionResponse toResponse(RolePermission rolePermission);
}
