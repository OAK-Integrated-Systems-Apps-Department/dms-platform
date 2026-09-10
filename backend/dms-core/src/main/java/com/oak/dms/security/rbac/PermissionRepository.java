package com.oak.dms.security.rbac;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Permission}. Hybrid tenant-scoping (see that
 * class's doc comment): every read here that's reachable from a tenant
 * admin's own screens filters to "shared (tenant_id IS NULL) OR owned by
 * this caller's tenant" - never another tenant's custom permissions, not
 * even a 403, since even revealing one exists would leak that tenant's
 * data. {@link #findByPermissionIdAndDeletedFalse} is the one exception,
 * used only by {@link RolePermissionServiceImpl#grantPermission} and
 * {@link RolePermissionRepository#findEffectivePermissionCodes}, both of
 * which do their own tenant check afterward rather than baking it into
 * the query itself (see those call sites).
 */
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByPermissionIdAndDeletedFalse(UUID permissionId);

    /**
     * Same lookup as {@link #findByPermissionIdAndDeletedFalse}, but also
     * requires the permission to be visible to the given tenant (shared,
     * or owned by that tenant) - used by every tenant-facing read/write
     * (get/update/delete) so a permission that's private to a different
     * tenant behaves exactly like it doesn't exist.
     */
    @Query("""
            select p from Permission p
            where p.permissionId = :permissionId and p.deleted = false
              and (p.tenantId is null or p.tenantId = :tenantId)
            """)
    Optional<Permission> findVisibleToTenant(@Param("permissionId") UUID permissionId,
                                              @Param("tenantId") UUID tenantId);

    /** Global/shared catalog only - used to guard a new tenant-custom permission's code against colliding with a shared one. */
    boolean existsByPermissionCodeAndTenantIdIsNullAndDeletedFalse(String permissionCode);

    /** One tenant's own custom permissions only - used to guard a new tenant-custom permission's code against that tenant's own existing ones. */
    boolean existsByPermissionCodeAndTenantIdAndDeletedFalse(String permissionCode, UUID tenantId);

    /** Used to guard action deletion - an action with live permissions (shared or any tenant's) can't be removed. */
    boolean existsByActionIdAndDeletedFalse(UUID actionId);

    /**
     * The catalog a given tenant actually sees: the shared/default
     * permissions every tenant draws from, plus whatever that tenant has
     * created themselves - never another tenant's custom permissions.
     */
    @Query("""
            select p from Permission p
            where p.deleted = false and (p.tenantId is null or p.tenantId = :tenantId)
            """)
    Page<Permission> findAllVisibleToTenant(@Param("tenantId") UUID tenantId, Pageable pageable);
}
