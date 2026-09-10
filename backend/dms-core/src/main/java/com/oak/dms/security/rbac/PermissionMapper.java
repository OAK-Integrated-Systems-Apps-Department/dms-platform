package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.PermissionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct entity/DTO mapping for {@link Permission}. actionCode/moduleId/
 * moduleCode have no corresponding entity field (actionId is kept as a
 * plain UUID and resolved through {@link PermissionActionRepository} /
 * {@link PermissionModuleRepository} - see {@link Permission}), so the
 * generated mapping is extended with a manual overload that accepts the
 * resolved {@link PermissionAction} and {@link PermissionModule} as
 * separate parameters, matching {@link RolePermissionMapper}'s pattern for
 * permissionCode. `shared` is likewise derived (`tenantId == null`) rather
 * than a real entity field, so it's ignored in the generated mapping and
 * computed in the manual overload too.
 */
@Mapper(componentModel = "spring")
public interface PermissionMapper {

    @Mapping(target = "shared", ignore = true)
    @Mapping(target = "actionCode", ignore = true)
    @Mapping(target = "moduleId", ignore = true)
    @Mapping(target = "moduleCode", ignore = true)
    PermissionResponse toResponse(Permission permission);

    default PermissionResponse toResponse(Permission permission, PermissionAction action, PermissionModule module) {
        PermissionResponse base = toResponse(permission);
        return new PermissionResponse(base.permissionId(), base.permissionCode(), base.name(),
                base.description(), permission.getTenantId() == null, base.actionId(), action.getActionCode(),
                module.getModuleId(), module.getModuleCode(), base.createdAt(), base.updatedAt());
    }
}
