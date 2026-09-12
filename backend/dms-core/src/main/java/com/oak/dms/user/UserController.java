package com.oak.dms.user;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.user.dto.CreateUserRequest;
import com.oak.dms.user.dto.ProvisionUserRequest;
import com.oak.dms.user.dto.UpdateUserRequest;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile management, backed by Keycloak for authentication")
public class UserController {

    private final UserService userService;
    private final AuthorizationService authorizationService;

    @PostMapping("/me/provision")
    @Operation(summary = "Self-provision the local Oak DMS profile for the calling Keycloak principal")
    public ResponseEntity<UserResponse> provisionCurrentUser(JwtAuthenticationToken auth,
                                                             @Valid @RequestBody ProvisionUserRequest request) {
        Jwt jwt = auth.getToken();
        UUID keycloakId = requireUuidClaim(jwt, jwt.getSubject(), "sub");
        UUID tenantId = requireUuidClaim(jwt, jwt.getClaimAsString("tenant_id"), "tenant_id");
        String email = requireClaim(jwt, jwt.getClaimAsString("email"), "email");

        UserResponse response = userService.provisionCurrentUser(keycloakId, tenantId, email, request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + response.userId())).body(response);
    }

    @GetMapping("/me")
    @Operation(summary = "Get the calling user's own profile")
    public UserResponse getCurrentUser() {
        return userService.getCurrentUser();
    }

    @GetMapping("/me/platform-roles")
    @Operation(summary = "List every fixed platform role (PLATFORM_ADMIN, TENANT_ADMIN, MEMBER) the caller holds",
            description = "Unlike GET /{userId}/platform-roles below - which tenant-admin screens use to "
                    + "inspect some *other* user and deliberately excludes PLATFORM_ADMIN, see "
                    + "KeycloakUserService#PLATFORM_ROLE_NAMES for why - this is always the caller asking "
                    + "about themselves, so PLATFORM_ADMIN is included here. Read directly off the "
                    + "authorities already derived from the caller's own validated JWT for this request "
                    + "(see AuthorizationService#myPlatformRoles), not a Keycloak admin lookup. This is what "
                    + "the frontend calls to decide whether to show platform-admin-only screens, such as "
                    + "Permission Catalog create/edit/delete.")
    public List<String> getMyPlatformRoles() {
        return authorizationService.myPlatformRoles();
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_CREATE)")
    @Operation(summary = "Invite a new user into the caller's tenant")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + response.userId())).body(response);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_VIEW, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_UPDATE)")
    @Operation(summary = "Get a user by id, within the caller's tenant")
    public UserResponse getUser(@PathVariable UUID userId) {
        return userService.getUser(userId);
    }

    @GetMapping("/{userId}/platform-roles")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_VIEW, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_UPDATE)")
    @Operation(summary = "List the fixed platform roles (TENANT_ADMIN, MEMBER) this user holds",
            description = "These are Keycloak-only realm roles, never rows in the roles/user_roles "
                    + "tables - see GET /{userId}/roles (UserRoleController) for tenant-defined DMS "
                    + "roles instead. A user can hold both kinds at once; they're returned separately "
                    + "because only tenant-defined roles have permissions/descriptions/edit screens "
                    + "behind them. PLATFORM_ADMIN is deliberately excluded even if held - see "
                    + "KeycloakUserService#PLATFORM_ROLE_NAMES for why.")
    public List<String> getPlatformRoles(@PathVariable UUID userId) {
        return userService.getPlatformRoles(userId);
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_VIEW, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_UPDATE)")
    @Operation(summary = "List users in the caller's tenant")
    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        return PageResponse.from(userService.listUsers(pageable));
    }

    @PutMapping("/{userId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_UPDATE)")
    @Operation(summary = "Update a user's profile/status")
    public UserResponse updateUser(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(userId, request);
    }

    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_RESET_PASSWORD)")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a password reset email to a user in the caller's tenant. Sets a fresh "
            + "temporary password server-side and emails the user a link to choose their own - no "
            + "password is ever returned by this endpoint.")
    public void resetPassword(@PathVariable UUID userId) {
        userService.resetPassword(userId);
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_ACTIVATE_DEACTIVATE)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate (soft-delete) a user")
    public void deactivateUser(@PathVariable UUID userId) {
        userService.deactivateUser(userId);
    }

    @PostMapping("/{userId}/reactivate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_ACTIVATE_DEACTIVATE)")
    @Operation(summary = "Reactivate a previously deactivated (soft-deleted) user",
            description = "Undoes deactivateUser: clears the soft-delete, sets status back to active, and "
                    + "re-enables the Keycloak account. 409 if the user isn't currently deactivated.")
    public UserResponse reactivateUser(@PathVariable UUID userId) {
        return userService.reactivateUser(userId);
    }

    /**
     * Deliberately gated on {@code @authz.isTenantAdmin()} rather than a
     * granular {@code @authz.hasPermission(...)} check like every other
     * endpoint in this controller - true before the Stage 1 granular sweep
     * and still true after it, not an oversight. A hard delete has no
     * undo (unlike deactivateUser, reversible via reactivateUser), and
     * the fine-grained permission catalog is itself something any tenant
     * admin can grant to an arbitrary custom role via Role Permission
     * Configuration - so gating this on a permission code (even a
     * dedicated one; the catalog's own {@code otua.manage-users.delete}
     * exists but is intentionally never checked anywhere) would let a
     * tenant admin hand irreversible, cross-system account removal to any
     * role they define. Restricting it to the fixed TENANT_ADMIN Keycloak
     * realm role instead keeps that one destructive action outside the
     * same self-service permission system it would otherwise have to
     * trust. Do not "fix" this by wiring it to a permission code.
     */
    @DeleteMapping("/{userId}/permanent")
    @PreAuthorize("@authz.isTenantAdmin()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Permanently delete a user",
            description = "A genuine hard delete: removes the local profile row and the corresponding "
                    + "Keycloak account outright, unlike deactivateUser (soft-delete, reversible via "
                    + "reactivateUser). There is no undo. Restricted to the tenant admin - deliberately "
                    + "not gated by any permission code, unlike every other endpoint here.")
    public void purgeUser(@PathVariable UUID userId) {
        userService.purgeUser(userId);
    }

    /**
     * Self-provisioning is the one endpoint that reads identity straight out
     * of the JWT rather than from {@link com.oak.dms.security.auth.TenantContext},
     * since by definition no local user row exists yet to resolve context
     * from. That means it must validate those claims itself: a token that's
     * missing "sub" or "tenant_id", or has a non-UUID value in either, is a
     * client/configuration problem (wrong client, misconfigured protocol
     * mapper, stale token) - not something that should ever reach
     * UUID.fromString() unguarded and crash the request with a 500.
     */
    private UUID requireUuidClaim(Jwt jwt, String rawValue, String claimName) {
        String value = requireClaim(jwt, rawValue, claimName);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenClaimException(claimName, "is not a valid UUID: '" + value + "'");
        }
    }

    private String requireClaim(Jwt jwt, String value, String claimName) {
        if (value == null || value.isBlank()) {
            throw new InvalidTokenClaimException(claimName, "is missing from the access token");
        }
        return value;
    }

    /**
     * Maps to 400 Bad Request: the token was authentic (signature already
     * verified upstream) but does not carry what this endpoint needs to do
     * its job, which is a request-shape problem, not a server fault.
     */
    private static class InvalidTokenClaimException extends ApiException {
        InvalidTokenClaimException(String claimName, String problem) {
            super(HttpStatus.BAD_REQUEST, "Access token claim '" + claimName + "' " + problem);
        }
    }
}