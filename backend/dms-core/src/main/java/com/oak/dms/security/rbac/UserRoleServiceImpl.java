package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.AssignRoleRequest;
import com.oak.dms.security.rbac.dto.UserRoleResponse;
import com.oak.dms.security.rbac.Role;
import com.oak.dms.user.User;
import com.oak.dms.security.rbac.UserRole;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.common.exception.TenantMismatchException;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.UserRoleMapper;
import com.oak.dms.security.rbac.RoleRepository;
import com.oak.dms.user.UserRepository;
import com.oak.dms.security.rbac.UserRoleRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.UserRoleService;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * See {@link UserRoleService}. Tenant-wide assignments (scopeType = tenant)
 * are mirrored into Keycloak as realm roles so coarse ROLE_* checks work
 * straight off the JWT; narrower scopes stay DB-only and are enforced
 * exclusively through {@link com.oak.dms.security.auth.AuthorizationService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserRoleServiceImpl implements UserRoleService {

    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleMapper userRoleMapper;
    private final KeycloakUserService keycloakUserService;

    @Override
    public UserRoleResponse assignRole(UUID userId, AssignRoleRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        User user = requireOwnedUser(userId, tenantId);
        Role role = roleRepository.findByRoleIdAndTenantIdAndDeletedFalse(request.roleId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", request.roleId()));

        UserRole.ScopeType scopeType = UserRole.ScopeType.valueOf(request.scopeType().name());
        if (scopeType != UserRole.ScopeType.tenant && request.scopeId() == null) {
            throw new ValidationException("scopeId is required when scopeType is " + scopeType);
        }
        UUID scopeId = scopeType == UserRole.ScopeType.tenant ? null : request.scopeId();

        if (userRoleRepository.existsByUserIdAndRoleIdAndScopeTypeAndScopeId(
                user.getUserId(), role.getRoleId(), scopeType, scopeId)) {
            throw new DuplicateResourceException("User already has this role at this scope");
        }

        UserRole assignment = UserRole.builder()
                .tenantId(tenantId)
                .userId(user.getUserId())
                .roleId(role.getRoleId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build();

        UserRole saved = userRoleRepository.save(assignment);

        // Tenant-wide role assignments are mirrored into Keycloak as realm
        // roles so coarse ROLE_* checks work straight off the JWT.
        // Narrower scopes (department/folder/document) stay DB-only and are
        // enforced exclusively via AuthorizationService / role_permissions.
        if (scopeType == UserRole.ScopeType.tenant) {
            keycloakUserService.assignRealmRole(tenantId, user.getKeycloakId(), role.getRoleName());
        }

        log.info("Assigned role {} to user {} (scope={}/{})", role.getRoleId(), userId, scopeType, scopeId);
        return userRoleMapper.toResponse(saved, role.getRoleName());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRoleResponse> listRolesForUser(UUID userId) {
        UUID tenantId = TenantContext.getTenantId();
        requireAnyOwnedUser(userId, tenantId);

        return userRoleRepository.findAllByUserIdAndTenantId(userId, tenantId).stream()
                .map(ur -> {
                    String roleName = roleRepository.findById(ur.getRoleId())
                            .map(Role::getRoleName)
                            .orElse("UNKNOWN");
                    return userRoleMapper.toResponse(ur, roleName);
                })
                .toList();
    }

    @Override
    public void revokeRole(UUID userId, UUID userRoleId) {
        UUID tenantId = TenantContext.getTenantId();
        User user = requireOwnedUser(userId, tenantId);

        UserRole assignment = userRoleRepository.findByIdAndTenantId(userRoleId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("UserRole", userRoleId));
        if (!assignment.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("UserRole", userRoleId);
        }

        userRoleRepository.delete(assignment);

        if (assignment.getScopeType() == UserRole.ScopeType.tenant) {
            roleRepository.findById(assignment.getRoleId())
                    .ifPresent(role -> keycloakUserService.removeRealmRole(
                            tenantId, user.getKeycloakId(), role.getRoleName()));
        }

        log.info("Revoked role assignment {} from user {}", userRoleId, userId);
    }

    private User requireOwnedUser(UUID userId, UUID tenantId) {
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getTenantId().equals(tenantId)) {
            throw new TenantMismatchException("User", userId);
        }
        return user;
    }

    /**
     * Same tenant-ownership check as {@link #requireOwnedUser}, but doesn't
     * exclude deactivated (soft-deleted) users — mirrors
     * {@code UserServiceImpl#findAnyOwnedByCallerTenant}.
     * {@code UserServiceImpl#deactivateUser} only soft-deletes a user
     * (sets {@code deleted = true}, doesn't remove the row), and
     * listUsers/getUser were deliberately updated to keep returning a
     * deactivated user rather than 404ing so the row stays visible with
     * an Inactive badge. {@link #listRolesForUser} needs the same
     * treatment: a deactivated user's previously-assigned roles still
     * need to be readable, or GET /api/v1/users/{id}/roles 404s on every
     * deactivated user, which — since the frontend's fetchAllUsers loads
     * every user's roles via Promise.all — takes the whole User List
     * fetch down with it, not just that one row.
     *
     * assignRole/revokeRole intentionally keep using the strict
     * {@link #requireOwnedUser} check above — a deactivated user's
     * existing roles should stay visible, but new grants/revokes
     * shouldn't be possible against a soft-deleted account.
     */
    private User requireAnyOwnedUser(UUID userId, UUID tenantId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.getTenantId().equals(tenantId)) {
            throw new TenantMismatchException("User", userId);
        }
        return user;
    }
}