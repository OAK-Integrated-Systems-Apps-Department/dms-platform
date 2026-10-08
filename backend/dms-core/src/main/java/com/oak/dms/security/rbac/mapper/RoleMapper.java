package com.oak.dms.security.rbac.mapper;

import com.oak.dms.security.rbac.entity.Role;

import com.oak.dms.security.rbac.dto.RoleResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct entity/DTO mapping for {@link Role}.
 */
@Mapper(componentModel = "spring")
public interface RoleMapper {
    RoleResponse toResponse(Role role);
}
