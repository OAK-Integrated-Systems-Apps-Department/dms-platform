package com.oak.dms.security.rbac.repository;

import com.oak.dms.security.rbac.entity.Permission;
import com.oak.dms.security.rbac.entity.PermissionAction;
import com.oak.dms.security.rbac.entity.PermissionModule;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.entity.RolePermission;
import com.oak.dms.security.rbac.entity.UserRole;
import com.oak.dms.security.rbac.service.impl.RoleServiceImpl;

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
     * Used to guard permission deletion - a permission still granted to
     * any role can't be removed from the catalog, mirroring how
     * deleteAction/deleteModule in PermissionActionServiceImpl /
     * PermissionModuleServiceImpl already guard their own children one
     * level up the hierarchy (see PermissionServiceImpl#deletePermission).
     */
    boolean existsByPermissionId(UUID permissionId);

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
     * Also joins to {@link PermissionAction} and {@link PermissionModule}
     * and requires both non-deleted, for the same reason: it's currently
     * impossible for a non-deleted {@link Permission} to have a deleted
     * action/module as its parent, but only because
     * {@code PermissionActionServiceImpl#deleteAction} /
     * {@code PermissionModuleServiceImpl#deleteModule} guard against it at
     * write time - the exact kind of "guaranteed by a rule elsewhere"
     * assumption that already broke twice in this codebase (both the Role
     * guards and {@link com.oak.dms.security.rbac.service.impl.PermissionServiceImpl#deletePermission}'s
     * own guard were silently lost and had to be reapplied). Checking it
     * here too means this query stays correct even if those guards are
     * ever lost again, not just today.
     * <p>
     * {@code p.tenantId is null or p.tenantId = :tenantId} defends the
     * same way against {@link Permission}'s own hybrid tenant-scoping: a
     * permission is either shared (tenantId null, granted to anyone) or
     * owned by one specific tenant. {@code RolePermissionServiceImpl#grantPermission}
     * already refuses to grant a role a permission outside its own tenant
     * boundary, so this should be unreachable in practice - included for
     * the same defense-in-depth reason as the action/module check just
     * above, not because it's currently exploitable.
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
            join PermissionAction a on a.actionId = p.actionId
            join PermissionModule m on m.moduleId = a.moduleId
            where ur.userId = :userId and ur.tenantId = :tenantId
              and p.deleted = false
              and (p.tenantId is null or p.tenantId = :tenantId)
              and a.deleted = false
              and m.deleted = false
              and r.deleted = false
              and r.status = com.oak.dms.security.rbac.entity.Role.RoleStatus.active
            """)
    List<String> findEffectivePermissionCodes(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);
}
