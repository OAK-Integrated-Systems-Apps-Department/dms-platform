package com.oak.dms.security.rbac.dto;

import com.oak.dms.security.rbac.entity.PermissionAction;

import com.oak.dms.security.rbac.dto.PermissionActionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct entity/DTO mapping for {@link PermissionAction}. moduleCode has
 * no corresponding entity field (it lives on the related PermissionModule
 * row, since moduleId is kept as a plain UUID - see {@link PermissionAction}),
 * so the generated mapping is extended with a manual overload that accepts
 * it as a separate parameter, matching {@link RolePermissionMapper}'s
 * pattern for permissionCode.
 */
@Mapper(componentModel = "spring")
public interface PermissionActionMapper {

    @Mapping(target = "moduleCode", ignore = true)
    PermissionActionResponse toResponse(PermissionAction action);

    default PermissionActionResponse toResponse(PermissionAction action, String moduleCode) {
        PermissionActionResponse base = toResponse(action);
        return new PermissionActionResponse(base.actionId(), base.moduleId(), moduleCode,
                base.actionCode(), base.actionName(), base.description(), base.createdAt(), base.updatedAt());
    }
}
