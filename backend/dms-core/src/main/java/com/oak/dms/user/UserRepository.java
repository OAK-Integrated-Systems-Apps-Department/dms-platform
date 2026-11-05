package com.oak.dms.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * Another user in the tenant - deactivated ones included, since uq_users_tenant_email covers
     * them too - already has this email, ignoring case.
     */
    boolean existsByTenantIdAndEmailIgnoreCaseAndUserIdNot(UUID tenantId, String email, UUID userId);

    /** Any user in the tenant - deactivated ones included (uq_users_tenant_username) - has this username, ignoring case. */
    boolean existsByTenantIdAndUsernameIgnoreCase(UUID tenantId, String username);

    /**
     * Active users of the tenant whose display name, username or email
     * contains {@code search} (lower-cased, wrapped in %), by name - for
     * people pickers. A null search lists everyone.
     */
    @Query("""
            select u from User u
            where u.tenantId = :tenantId and u.deleted = false and u.status = :status
              and (:search is null or lower(u.displayName) like :search or lower(u.username) like :search
                   or lower(u.email) like :search)
            order by u.displayName
            """)
    List<User> searchByStatus(@Param("tenantId") UUID tenantId, @Param("status") User.UserStatus status,
                              @Param("search") String search, Pageable pageable);

    default List<User> searchActive(UUID tenantId, String search, Pageable pageable) {
        return searchByStatus(tenantId, User.UserStatus.active, search, pageable);
    }

    // Includes deactivated (soft-deleted) users deliberately, unlike the
    // three lookups above: the User List / View User screens need to keep
    // showing a deactivated user (with status "disabled") so there's
    // somewhere to click Reactivate. See UserServiceImpl#getUser and
    // #listUsers, and UserServiceImpl#findOwnedByCallerTenant (still
    // deleted=false-only, used by updateUser/deactivateUser/resetPassword,
    // which genuinely shouldn't apply to an already-deactivated user).
    //
    // A user removed via UserServiceImpl#purgeUser (the User List's Delete
    // action) needs no filter here at all - unlike deactivateUser, purgeUser
    // is a real DELETE FROM users, so a purged user is simply gone from
    // this query's results already.
    @Query("select u from User u where u.tenantId = :tenantId")
    Page<User> findAllByTenant(@Param("tenantId") UUID tenantId, Pageable pageable);

    /** The tenant's users whose Keycloak id is one of keycloakIds - picks the tenant admins out of a realm-role member list. */
    java.util.List<User> findAllByTenantIdAndKeycloakIdInAndDeletedFalse(UUID tenantId, java.util.Collection<UUID> keycloakIds);

    java.util.List<User> findAllByUserIdInAndTenantIdAndDeletedFalse(java.util.Collection<UUID> userIds, UUID tenantId);
}