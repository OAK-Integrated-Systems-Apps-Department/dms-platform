package com.oak.dms.security.rbac.repository;

import com.oak.dms.security.rbac.entity.UserRole;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link UserRole}.
 */
public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    @Query("select ur from UserRole ur where ur.userId = :userId and ur.tenantId = :tenantId")
    List<UserRole> findAllByUserIdAndTenantId(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    @Query("select ur from UserRole ur where ur.userRoleId = :id and ur.tenantId = :tenantId")
    Optional<UserRole> findByIdAndTenantId(@Param("id") UUID userRoleId, @Param("tenantId") UUID tenantId);

    boolean existsByUserIdAndRoleIdAndScopeTypeAndScopeId(UUID userId, UUID roleId,
                                                            UserRole.ScopeType scopeType, UUID scopeId);

    /**
     * Used to guard deactivating and deleting a role - a role still
     * assigned to any user can be neither (see RoleServiceImpl#updateRole
     * / #deleteRole), the same general shape as the existing
     * deletePermission guard in PermissionServiceImpl (a permission still
     * granted to a role can't be removed) and deleteAction/deleteModule
     * one level up from that.
     */
    boolean existsByRoleId(UUID roleId);

    /**
     * Names of the tenant roles that currently apply to the user - the same
     * "applies" rule as RolePermissionRepository#findEffectivePermissionCodes
     * (role not deleted, status active), so what My Profile lists always
     * matches what the user's permissions are actually derived from.
     * Distinct because one role can be assigned at several scopes.
     */
    @Query("""
            select distinct r.roleName
            from UserRole ur
            join Role r on r.roleId = ur.roleId
            where ur.userId = :userId and ur.tenantId = :tenantId
              and r.deleted = false
              and r.status = com.oak.dms.security.rbac.entity.Role.RoleStatus.active
            order by r.roleName
            """)
    List<String> findActiveRoleNames(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    /** Ids of the active roles the user holds - who they are when a folder lists roles (V53). */
    @Query("""
            select distinct r.roleId
            from UserRole ur
            join Role r on r.roleId = ur.roleId
            where ur.userId = :userId and ur.tenantId = :tenantId
              and r.deleted = false
              and r.status = com.oak.dms.security.rbac.entity.Role.RoleStatus.active
            """)
    List<UUID> findActiveRoleIds(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);
}
