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
 * columns.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RolePermissionServiceImpl implements RolePermissionService {

    private final RolePermissionRepository rolePermissionRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionMapper rolePermissionMapper;

    @Override
    public RolePermissionResponse grantPermission(UUID roleId, GrantPermissionRequest request) {
        Role role = requireOwnedRole(roleId);

        if (rolePermissionRepository.existsByRoleIdAndPermissionCodeAndResourceScope(
                role.getRoleId(), request.permissionCode(), request.resourceScope())) {
            throw new DuplicateResourceException(
                    "Role already has permission " + request.permissionCode()
                            + (request.resourceScope() != null ? " on scope " + request.resourceScope() : ""));
        }

        RolePermission grant = RolePermission.builder()
                .tenantId(role.getTenantId())
                .roleId(role.getRoleId())
                .permissionCode(request.permissionCode())
                .resourceScope(request.resourceScope())
                .build();

        RolePermission saved = rolePermissionRepository.save(grant);
        log.info("Granted permission {} to role {}", request.permissionCode(), roleId);
        return rolePermissionMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RolePermissionResponse> listPermissionsForRole(UUID roleId) {
        requireOwnedRole(roleId);
        return rolePermissionRepository.findAllByRoleId(roleId).stream()
                .map(rolePermissionMapper::toResponse)
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
