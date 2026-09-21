package com.oak.dms.security.rbac.service;

import com.oak.dms.security.rbac.dto.CreateRoleRequest;
import com.oak.dms.security.rbac.dto.UpdateRoleRequest;
import com.oak.dms.security.rbac.dto.RoleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Role lifecycle within a tenant: create, lookup, list, update, delete.
 */
public interface RoleService {
    RoleResponse createRole(CreateRoleRequest request);

    RoleResponse getRole(UUID roleId);

    Page<RoleResponse> listRoles(Pageable pageable);

    RoleResponse updateRole(UUID roleId, UpdateRoleRequest request);

    void deleteRole(UUID roleId);
}
