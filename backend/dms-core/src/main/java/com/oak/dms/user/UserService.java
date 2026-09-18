package com.oak.dms.user;

import com.oak.dms.user.dto.CreateUserRequest;
import com.oak.dms.user.dto.ProvisionUserRequest;
import com.oak.dms.user.dto.UpdateOwnProfileRequest;
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
    UserResponse provisionCurrentUser(UUID keycloakId, UUID tenantId, String email, String username,
                                       ProvisionUserRequest request);

    /** Tenant-admin invites a brand-new user; creates both the Keycloak account and the local profile. */
    UserResponse createUser(CreateUserRequest request);

    UserResponse getUser(UUID userId);

    UserResponse getCurrentUser();

    /**
     * A user updating their own first/last name - see
     * {@link UpdateOwnProfileRequest}'s doc comment for why this takes a
     * narrower request type than {@link #updateUser}, which is the
     * admin-facing equivalent for editing some *other* user.
     */
    UserResponse updateCurrentUser(UpdateOwnProfileRequest request);

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

    /**
     * @param reason Why the account is being deactivated - mandatory (see
     *               AuditActionCatalog's USER_DEACTIVATED entry): sanitized
     *               and recorded on the resulting audit event, never stored
     *               anywhere on the user row itself.
     */
    void deactivateUser(UUID userId, String reason);

    /**
     * Permanently removes a user from the tenant — a genuine hard delete
     * (the row, and the corresponding Keycloak account, are both actually
     * gone), unlike {@link #deactivateUser}, which the User List's
     * Activate/Deactivate toggle uses and which only soft-deletes, keeping
     * the row visible with a "Reactivate" action (see
     * {@link UserRepository#findAllByTenant}'s own doc comment). There is
     * no corresponding "un-delete" the way {@link #reactivateUser} undoes
     * a deactivation — once purged, the user is gone for good. Available
     * to the tenant admin unconditionally, and delegable via
     * {@code otua.manage-users.delete} — see {@code UserController#purgeUser}'s
     * {@code @PreAuthorize}.
     */
    void purgeUser(UUID userId);

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