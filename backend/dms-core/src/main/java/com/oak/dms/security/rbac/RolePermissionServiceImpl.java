package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.GrantPermissionRequest;
import com.oak.dms.security.rbac.dto.RolePermissionResponse;
import com.oak.dms.security.rbac.Role;
import com.oak.dms.security.rbac.RolePermission;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.rbac.RolePermissionMapper;
import com.oak.dms.security.rbac.RolePermissionRepository;
import com.oak.dms.security.rbac.RoleRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.RolePermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * See {@link RolePermissionService}. Grants are immutable once created -
 * revoking removes the row outright rather than editing it in place, which
 * is why {@link com.oak.dms.security.rbac.RolePermission} has no update
 * columns. This is the write side of the Role &lt;-&gt; Permission
 * many-to-many join: {@link #grantPermission} adds a row, {@link #revokePermission}
 * removes one, and the same permission can be granted to any number of
 * different roles independently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RolePermissionServiceImpl implements RolePermissionService {

    private final RolePermissionRepository rolePermissionRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionMapper rolePermissionMapper;

    @Override
    public RolePermissionResponse grantPermission(UUID roleId, GrantPermissionRequest request) {
        Role role = requireOwnedRole(roleId);
        Permission permission = permissionRepository.findByPermissionIdAndDeletedFalse(request.permissionId())
                .orElseThrow(() -> new ResourceNotFoundException("Permission", request.permissionId()));

        if (rolePermissionRepository.existsByRoleIdAndPermissionIdAndResourceScope(
                role.getRoleId(), permission.getPermissionId(), request.resourceScope())) {
            throw new DuplicateResourceException(
                    "Role already has permission " + permission.getPermissionCode()
                            + (request.resourceScope() != null ? " on scope " + request.resourceScope() : ""));
        }

        RolePermission grant = RolePermission.builder()
                .tenantId(role.getTenantId())
                .roleId(role.getRoleId())
                .permissionId(permission.getPermissionId())
                .resourceScope(request.resourceScope())
                .build();

        RolePermission saved = rolePermissionRepository.save(grant);
        log.info("Granted permission {} to role {}", permission.getPermissionCode(), roleId);
        return rolePermissionMapper.toResponse(saved, permission.getPermissionCode());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RolePermissionResponse> listPermissionsForRole(UUID roleId) {
        requireOwnedRole(roleId);
        return rolePermissionRepository.findAllByRoleId(roleId).stream()
                .map(rp -> {
                    String permissionCode = permissionRepository.findById(rp.getPermissionId())
                            .map(Permission::getPermissionCode)
                            .orElse("UNKNOWN");
                    return rolePermissionMapper.toResponse(rp, permissionCode);
                })
                .toList();
    }

    @Override
    public void revokePermission(UUID roleId, UUID rolePermissionId) {
        requireOwnedRole(roleId);
        RolePermission grant = rolePermissionRepository.findByIdAndTenantId(rolePermissionId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("RolePermission", rolePermissionId));

        if (!grant.getRoleId().equals(roleId)) {
            throw new ResourceNotFoundException("RolePermission", rolePermissionId);
        }

        rolePermissionRepository.delete(grant);
        log.info("Revoked permission grant {} from role {}", rolePermissionId, roleId);
    }

    private Role requireOwnedRole(UUID roleId) {
        return roleRepository.findByRoleIdAndTenantIdAndDeletedFalse(roleId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));
    }
}
