package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.AssignRoleRequest;
import com.oak.dms.security.rbac.dto.UserRoleResponse;

import java.util.List;
import java.util.UUID;

/**
 * Assigns, lists, and revokes a user's role assignments, optionally scoped
 * narrower than the whole tenant.
 */
public interface UserRoleService {
    UserRoleResponse assignRole(UUID userId, AssignRoleRequest request);

    List<UserRoleResponse> listRolesForUser(UUID userId);

    void revokeRole(UUID userId, UUID userRoleId);
}
