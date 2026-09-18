package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.RoleResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct entity/DTO mapping for {@link Role}.
 */
@Mapper(componentModel = "spring")
public interface RoleMapper {
    RoleResponse toResponse(Role role);
}
