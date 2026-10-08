package com.oak.dms.security.rbac.service.impl;

import com.oak.dms.security.rbac.mapper.RoleMapper;

import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.security.rbac.service.RoleService;

import com.oak.dms.security.rbac.dto.CreateRoleRequest;
import com.oak.dms.security.rbac.dto.UpdateRoleRequest;
import com.oak.dms.security.rbac.dto.RoleResponse;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.InvalidRoleStateException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * See {@link RoleService}. Deletion is a soft-delete, consistent with
 * {@link com.oak.dms.tenant.TenantServiceImpl} and
 * {@link com.oak.dms.user.UserServiceImpl}. Both deactivating (via
 * {@link #updateRole}) and deleting (via {@link #deleteRole}) a role are
 * blocked while it's still assigned to any user - see those methods for
 * why.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final RoleMapper roleMapper;
    private final KeycloakUserService keycloakUserService;
    private final UserRoleRepository userRoleRepository;
    private final AuditService auditService;

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
                .status(request.status() == null
                        ? Role.RoleStatus.active
                        : Role.RoleStatus.valueOf(request.status().name()))
                .createdBy(TenantContext.getUserId())
                .build();

        Role saved = roleRepository.save(role);
        log.info("Created role {} ({}) in tenant {}", saved.getRoleId(), saved.getRoleName(), tenantId);

        auditService.event(AuditActionCatalog.ROLE_CREATED)
                .objectType(AuditObjectTypes.ROLE)
                .objectId(saved.getRoleId())
                .resourceName(saved.getRoleName())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "roleName", saved.getRoleName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription(),
                        "status", saved.getStatus()))
                .log();

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
        Map<String, Object> previousValue = Map.of(
                "roleName", role.getRoleName(),
                "description", role.getDescription() == null ? "" : role.getDescription(),
                "status", role.getStatus());

        // Deactivating a role while it's still assigned to a user would be
        // easy to mistake for actually cutting off that user's access -
        // it currently doesn't (findEffectivePermissionCodes has no join
        // back to Role at all, so an assigned user keeps every permission
        // an "Inactive" role grants them, completely unaffected by this
        // flag). Until/unless that's changed to make Inactive genuinely
        // revoke access, blocking the transition here is the safer
        // default: it forces unassigning the role first, an explicit,
        // visible action, rather than letting deactivation look like it
        // did something it silently didn't.
        boolean deactivating = request.status() == UpdateRoleRequest.Status.inactive
                && role.getStatus() == Role.RoleStatus.active;
        if (deactivating && userRoleRepository.existsByRoleId(roleId)) {
            throw new InvalidRoleStateException(
                    "Cannot deactivate role '" + role.getRoleName()
                            + "' while it is still assigned to one or more users. "
                            + "Remove it from every user first.");
        }

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
        role.setStatus(Role.RoleStatus.valueOf(request.status().name()));
        role.setUpdatedBy(TenantContext.getUserId());
        role.setUpdatedAt(OffsetDateTime.now());

        Role saved = roleRepository.save(role);
        log.info("Updated role {}", saved.getRoleId());

        auditService.event(AuditActionCatalog.ROLE_UPDATED)
                .objectType(AuditObjectTypes.ROLE)
                .objectId(saved.getRoleId())
                .resourceName(saved.getRoleName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "roleName", saved.getRoleName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription(),
                        "status", saved.getStatus()))
                .log();

        return roleMapper.toResponse(saved);
    }

    @Override
    public void deleteRole(UUID roleId) {
        Role role = findOwnedByCallerTenant(roleId);

        // Same reasoning as the deactivate guard in updateRole, but more
        // pressing here: deleting a role still assigned to a user would
        // orphan every row in user_roles pointing at it - RoleResponse
        // becomes unreachable (findOwnedByCallerTenant filters on
        // deletedFalse), but findEffectivePermissionCodes has no such
        // filter, so the assigned user would keep silently exercising
        // every permission that now-deleted role granted, with nothing
        // left in Role Management even showing it exists to revoke.
        if (userRoleRepository.existsByRoleId(roleId)) {
            throw new InvalidRoleStateException(
                    "Cannot delete role '" + role.getRoleName()
                            + "' while it is still assigned to one or more users. "
                            + "Remove it from every user first.");
        }

        String roleName = role.getRoleName();

        role.setDeleted(true);
        role.setDeletedAt(OffsetDateTime.now());
        role.setUpdatedBy(TenantContext.getUserId());
        role.setUpdatedAt(OffsetDateTime.now());
        roleRepository.save(role);
        log.info("Deleted role {}", roleId);

        auditService.event(AuditActionCatalog.ROLE_DELETED)
                .objectType(AuditObjectTypes.ROLE)
                .objectId(roleId)
                .resourceName(roleName)
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    private Role findOwnedByCallerTenant(UUID roleId) {
        return roleRepository.findByRoleIdAndTenantIdAndDeletedFalse(roleId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));
    }
}
