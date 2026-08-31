package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Role}, scoped to a single tenant on every query -
 * roles are never looked up by id alone, always id + tenantId.
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByRoleIdAndTenantIdAndDeletedFalse(UUID roleId, UUID tenantId);

    boolean existsByTenantIdAndRoleNameAndDeletedFalse(UUID tenantId, String roleName);

    /**
     * Realm-wide (cross-tenant) name check. Needed because the DMS role
     * name is mirrored verbatim into Keycloak as the realm role name (see
     * {@link com.oak.dms.security.keycloak.KeycloakUserService}), and
     * Keycloak realm roles are unique per realm, not per tenant, even
     * though every tenant shares one realm. {@code roleName} is only
     * unique per-tenant in this table ({@code uq_roles_tenant_name}), so
     * this must be checked separately before creating/renaming a role.
     */
    boolean existsByRoleNameAndDeletedFalse(String roleName);

    @Query("select r from Role r where r.tenantId = :tenantId and r.deleted = false")
    Page<Role> findAllActiveByTenant(@Param("tenantId") UUID tenantId, Pageable pageable);
}
