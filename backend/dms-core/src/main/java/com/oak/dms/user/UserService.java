package com.oak.dms.user;

import com.oak.dms.user.dto.CreateUserRequest;
import com.oak.dms.user.dto.ProvisionUserRequest;
import com.oak.dms.user.dto.UpdateUserRequest;
import com.oak.dms.user.dto.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * Called once, from {@link com.oak.dms.security.auth.TenantContextFilter},
     * the first time a user's JWT resolves successfully — proof they've
     * completed both the VERIFY_EMAIL and UPDATE_PASSWORD required actions
     * Keycloak gates login on (see KeycloakUserService#createUser). A no-op
     * if the user is already verified, so it's safe to call on every
     * request without checking first.
     */
    void markVerified(UUID userId);

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
}