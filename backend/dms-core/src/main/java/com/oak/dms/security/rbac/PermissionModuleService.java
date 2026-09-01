package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.CreatePermissionModuleRequest;
import com.oak.dms.security.rbac.dto.PermissionModuleResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionModuleRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Lifecycle of the platform-wide permission module catalog: create,
 * lookup, list, update (metadata only, not the code), delete. Not
 * tenant-scoped - see {@link PermissionModule}.
 */
public interface PermissionModuleService {
    PermissionModuleResponse createModule(CreatePermissionModuleRequest request);

    PermissionModuleResponse getModule(UUID moduleId);

    Page<PermissionModuleResponse> listModules(Pageable pageable);

    PermissionModuleResponse updateModule(UUID moduleId, UpdatePermissionModuleRequest request);

    void deleteModule(UUID moduleId);
}
