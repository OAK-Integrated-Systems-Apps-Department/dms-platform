package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link RolePermission}. findEffectivePermissionCodes is
 * the query {@link com.oak.dms.security.auth.AuthorizationService} runs on
 * every permission check - it joins through UserRole to collect every
 * permission code the caller holds via any of their role assignments in the
 * current tenant.
 */
public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

    @Query("select rp from RolePermission rp where rp.roleId = :roleId")
    List<RolePermission> findAllByRoleId(@Param("roleId") UUID roleId);

    @Query("select rp from RolePermission rp where rp.rolePermissionId = :id and rp.tenantId = :tenantId")
    Optional<RolePermission> findByIdAndTenantId(@Param("id") UUID rolePermissionId, @Param("tenantId") UUID tenantId);

    boolean existsByRoleIdAndPermissionIdAndResourceScope(UUID roleId, UUID permissionId, String resourceScope);

    /**
     * Effective permission codes for a user across all of their role
     * assignments in the given tenant, resolved through the Permission
     * catalog now that role_permissions references it by id rather than
     * storing the code as a free-form string. Used by the authorization
     * layer to build the permission set for a request.
     * <p>
     * Joins back to {@link Role} and requires it to be both active and
     * not deleted - previously this had no awareness of the role's own
     * state at all, so an assigned user kept every permission an
     * "Inactive" role granted them completely unaffected by that flag,
     * and (had a role ever ended up deleted while still assigned - now
     * prevented going forward by the guards in
     * {@link RoleServiceImpl#updateRole}/{@link RoleServiceImpl#deleteRole})
     * would have kept doing so with no trace of the role left in Role
     * Management to even reveal the problem. This closes that off at the
     * one place that actually matters for enforcement, rather than
     * relying solely on those guards to prevent the state from ever being
     * reached - covers any pre-existing orphaned {@code user_roles} rows
     * too, not just new activity going forward.
     * <p>
     * {@code r.status = ...active} uses a fully-qualified JPQL enum
     * literal rather than a bound parameter, matching the existing
     * pattern in {@code OutboxEventRepository} - comparing a
     * {@code @Enumerated(STRING)} field against a raw string literal
     * isn't reliably portable in JPQL, and there's no reason to make the
     * caller pass in a constant that's always the same value.
     */
    @Query("""
            select distinct p.permissionCode
            from RolePermission rp
            join UserRole ur on ur.roleId = rp.roleId
            join Role r on r.roleId = rp.roleId
            join Permission p on p.permissionId = rp.permissionId
            where ur.userId = :userId and ur.tenantId = :tenantId
              and p.deleted = false
              and r.deleted = false
              and r.status = com.oak.dms.security.rbac.Role.RoleStatus.active
            """)
    List<String> findEffectivePermissionCodes(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);
}
