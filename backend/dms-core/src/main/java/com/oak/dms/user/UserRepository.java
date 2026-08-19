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

    @Query("select u from User u where u.tenantId = :tenantId and u.deleted = false")
    Page<User> findAllActiveByTenant(@Param("tenantId") UUID tenantId, Pageable pageable);
}
