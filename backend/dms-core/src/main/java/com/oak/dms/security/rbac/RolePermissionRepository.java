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

    boolean existsByRoleIdAndPermissionCodeAndResourceScope(UUID roleId, String permissionCode, String resourceScope);

    /**
     * Effective permission codes for a user across all of their role
     * assignments in the given tenant. Used by the authorization layer
     * to build the permission set for a request.
     */
    @Query("""
            select distinct rp.permissionCode
            from RolePermission rp
            join UserRole ur on ur.roleId = rp.roleId
            where ur.userId = :userId and ur.tenantId = :tenantId
            """)
    List<String> findEffectivePermissionCodes(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);
}
