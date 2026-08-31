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

    UserResponse updateUser(UUID userId, UpdateUserRequest request);

    void deactivateUser(UUID userId);

    /** Tenant-admin triggers a password reset email for one of their users. */
    void resetPassword(UUID userId);
}
