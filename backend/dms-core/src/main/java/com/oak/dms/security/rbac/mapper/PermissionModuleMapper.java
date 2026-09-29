package com.oak.dms.security.rbac.mapper;

import com.oak.dms.security.rbac.entity.PermissionModule;

import com.oak.dms.security.rbac.dto.PermissionModuleResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct entity/DTO mapping for {@link PermissionModule}.
 */
@Mapper(componentModel = "spring")
public interface PermissionModuleMapper {
    PermissionModuleResponse toResponse(PermissionModule module);
}
