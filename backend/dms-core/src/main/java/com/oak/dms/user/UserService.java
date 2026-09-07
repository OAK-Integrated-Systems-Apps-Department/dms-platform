package com.oak.dms.user;

import com.oak.dms.user.dto.CreateUserRequest;
import com.oak.dms.user.dto.ProvisionUserRequest;
import com.oak.dms.user.dto.UpdateUserRequest;
import com.oak.dms.user.dto.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * User lifecycle within a tenant: self-provisioning after first Keycloak
 * login, tenant-admin invitation of new users, lookup, update, and
 * deactivation.
 */
public interface UserService {

    /** Self-provisioning: called by an already-authenticated Keycloak principal. */
    UserResponse provisionCurrentUser(UUID keycloakId, UUID tenantId, String email, ProvisionUserRequest request);

    /** Tenant-admin invites a brand-new user; creates both the Keycloak account and the local profile. */
    UserResponse createUser(CreateUserRequest request);

    UserResponse getUser(UUID userId);

    UserResponse getCurrentUser();

    Page<UserResponse> listUsers(Pageable pageable);

    UserResponse updateUser(UUID userId, UpdateUserRequest request);

    void deactivateUser(UUID userId);

    /**
     * Undoes {@link #deactivateUser}: clears the soft-delete, sets status  
     * back to active, and re-enables the Keycloak account. Only valid for a
     * user that actually went through deactivateUser — a user whose status
     * was changed via {@link #updateUser} (locked/disabled, never deleted)
     * isn't a valid target here; that direction reverses via updateUser's
     * own status field instead.
     */
    UserResponse reactivateUser(UUID userId);

    /** Tenant-admin triggers a password reset email for one of their users. */
    void resetPassword(UUID userId);

    /**
     * The fixed platform roles (PLATFORM_ADMIN, TENANT_ADMIN, MEMBER) this
     * user directly holds in Keycloak, if any. These are never rows in the
     * {@code roles}/{@code user_roles} tables — see
     * {@link com.oak.dms.security.keycloak.KeycloakUserService#getPlatformRealmRoles}
     * for why this needs its own call rather than reading local state.
     */
    List<String> getPlatformRoles(UUID userId);
}