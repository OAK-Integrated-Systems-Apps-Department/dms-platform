package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.UserRole;
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
}
