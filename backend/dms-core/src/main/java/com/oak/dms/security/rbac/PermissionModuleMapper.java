package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.PermissionModuleResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct entity/DTO mapping for {@link PermissionModule}.
 */
@Mapper(componentModel = "spring")
public interface PermissionModuleMapper {
    PermissionModuleResponse toResponse(PermissionModule module);
}
