package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.CreateRoleRequest;
import com.oak.dms.security.rbac.dto.UpdateRoleRequest;
import com.oak.dms.security.rbac.dto.RoleResponse;
import com.oak.dms.security.rbac.Role;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.rbac.RoleMapper;
import com.oak.dms.security.rbac.RoleRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * See {@link RoleService}. Deletion is a soft-delete, consistent with
 * {@link com.oak.dms.tenant.TenantServiceImpl} and
 * {@link com.oak.dms.user.UserServiceImpl}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final RoleMapper roleMapper;
    private final KeycloakUserService keycloakUserService;

    @Override
    public RoleResponse createRole(CreateRoleRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        if (roleRepository.existsByTenantIdAndRoleNameAndDeletedFalse(tenantId, request.roleName())) {
            throw new DuplicateResourceException("A role already exists named " + request.roleName());
        }
        // Keycloak realm roles are unique per realm and all tenants share
        // one realm (see KeycloakUserService), so a name that's free within
        // this tenant can still collide with another tenant's role. Check
        // globally too, or the Keycloak call below fails with a 409 that's
        // much less clear to the caller than this one.
        if (roleRepository.existsByRoleNameAndDeletedFalse(request.roleName())) {
            throw new DuplicateResourceException(
                    "Role name '" + request.roleName() + "' is not available. Please choose another name.");
        }

        // Tenant-wide assignments of this role are mirrored into Keycloak as
        // a realm role (see UserRoleServiceImpl), so the realm role must
        // exist before any assignment can succeed. Created before the DB
        // write so a Keycloak failure aborts the transaction cleanly rather
        // than leaving a DMS role with no corresponding realm role.
        keycloakUserService.createRealmRole(tenantId, request.roleName(), request.description());

        Role role = Role.builder()
                .tenantId(tenantId)
                .roleName(request.roleName())
                .description(request.description())
                .createdBy(TenantContext.getUserId())
                .build();

        Role saved = roleRepository.save(role);
        log.info("Created role {} ({}) in tenant {}", saved.getRoleId(), saved.getRoleName(), tenantId);
        return roleMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public RoleResponse getRole(UUID roleId) {
        Role role = findOwnedByCallerTenant(roleId);
        return roleMapper.toResponse(role);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoleResponse> listRoles(Pageable pageable) {
        return roleRepository.findAllActiveByTenant(TenantContext.getTenantId(), pageable)
                .map(roleMapper::toResponse);
    }

    @Override
    public RoleResponse updateRole(UUID roleId, UpdateRoleRequest request) {
        Role role = findOwnedByCallerTenant(roleId);

        // Keep the mirrored Keycloak realm role name in sync on rename, so
        // future assign/revoke calls (which look the role up by current
        // name) keep resolving correctly.
        if (!request.roleName().equals(role.getRoleName())) {
            if (roleRepository.existsByRoleNameAndDeletedFalse(request.roleName())) {
                throw new DuplicateResourceException(
                        "Role name '" + request.roleName() + "' is not available. Please choose another name.");
            }
            keycloakUserService.renameRealmRole(role.getTenantId(), role.getRoleName(), request.roleName());
        }

        role.setRoleName(request.roleName());
        role.setDescription(request.description());
        role.setUpdatedBy(TenantContext.getUserId());
        role.setUpdatedAt(OffsetDateTime.now());

        Role saved = roleRepository.save(role);
        log.info("Updated role {}", saved.getRoleId());
        return roleMapper.toResponse(saved);
    }

    @Override
    public void deleteRole(UUID roleId) {
        Role role = findOwnedByCallerTenant(roleId);
        role.setDeleted(true);
        role.setDeletedAt(OffsetDateTime.now());
        role.setUpdatedBy(TenantContext.getUserId());
        role.setUpdatedAt(OffsetDateTime.now());
        roleRepository.save(role);
        log.info("Deleted role {}", roleId);
    }

    private Role findOwnedByCallerTenant(UUID roleId) {
        return roleRepository.findByRoleIdAndTenantIdAndDeletedFalse(roleId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));
    }
}