package com.oak.dms.user;

import com.oak.dms.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link User}. findByKeycloakIdAndDeletedFalse is the
 * lookup {@link com.oak.dms.security.auth.TenantContextFilter} uses on every
 * authenticated request to resolve the caller's local profile from their JWT.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUserIdAndTenantIdAndDeletedFalse(UUID userId, UUID tenantId);

    Optional<User> findByKeycloakIdAndDeletedFalse(UUID keycloakId);

    Optional<User> findByTenantIdAndEmailAndDeletedFalse(UUID tenantId, String email);

    boolean existsByTenantIdAndEmailAndDeletedFalse(UUID tenantId, String email);

    // Includes deactivated (soft-deleted) users deliberately, unlike the
    // three lookups above: the User List / View User screens need to keep
    // showing a deactivated user (with status "disabled") so there's
    // somewhere to click Reactivate. See UserServiceImpl#getUser and
    // #listUsers, and UserServiceImpl#findOwnedByCallerTenant (still
    // deleted=false-only, used by updateUser/deactivateUser/resetPassword,
    // which genuinely shouldn't apply to an already-deactivated user).
    @Query("select u from User u where u.tenantId = :tenantId")
    Page<User> findAllByTenant(@Param("tenantId") UUID tenantId, Pageable pageable);
}