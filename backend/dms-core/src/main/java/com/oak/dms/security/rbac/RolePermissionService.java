package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.GrantPermissionRequest;
import com.oak.dms.security.rbac.dto.RolePermissionResponse;

import java.util.List;
import java.util.UUID;

/**
 * Grants, lists, and revokes permission codes on a role.
 */
public interface RolePermissionService {
    RolePermissionResponse grantPermission(UUID roleId, GrantPermissionRequest request);

    List<RolePermissionResponse> listPermissionsForRole(UUID roleId);

    void revokePermission(UUID roleId, UUID rolePermissionId);
}
