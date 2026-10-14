package com.oak.dms.user;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.user.dto.ChangeOwnPasswordRequest;
import com.oak.dms.user.dto.UpdateOwnIdentityRequest;
import com.oak.dms.user.dto.CreateUserRequest;
import com.oak.dms.user.dto.DeactivateUserRequest;
import com.oak.dms.user.dto.ProvisionUserRequest;
import com.oak.dms.user.dto.UpdateOwnProfileRequest;
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
    private final AccountSelfService accountSelfService;

    @PostMapping("/me/provision")
    @Operation(summary = "Self-provision the calling Keycloak principal's local user profile")
    public ResponseEntity<UserResponse> provisionCurrentUser(JwtAuthenticationToken auth,
                                                             @Valid @RequestBody ProvisionUserRequest request) {
        Jwt jwt = auth.getToken();
        UUID keycloakId = requireUuidClaim(jwt, jwt.getSubject(), "sub");
        UUID tenantId = requireUuidClaim(jwt, jwt.getClaimAsString("tenant_id"), "tenant_id");
        String email = requireClaim(jwt, jwt.getClaimAsString("email"), "email");
        // The account's real Keycloak username, standard OIDC claim Keycloak
        // always includes - never re-derived from the email here, unlike
        // the client-side placeholder this replaced (see User#getUsername's
        // own doc comment).
        String username = requireClaim(jwt, jwt.getClaimAsString("preferred_username"), "preferred_username");

        UserResponse response = userService.provisionCurrentUser(keycloakId, tenantId, email, username, request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + response.userId())).body(response);
    }

    @GetMapping("/me")
    @Operation(summary = "Get the calling user's own profile")
    public UserResponse getCurrentUser() {
        return userService.getCurrentUser();
    }

    @PutMapping("/me")
    @Operation(summary = "Update the calling user's own first/last name",
            description = "Deliberately narrower than PUT /{userId} (the admin-facing equivalent): only "
                    + "firstName/lastName are accepted, so a caller can never use this to change their own "
                    + "tenant, role assignments, MFA flag, or status - see UpdateOwnProfileRequest.")
    public UserResponse updateCurrentUser(@Valid @RequestBody UpdateOwnProfileRequest request) {
        return userService.updateCurrentUser(request);
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

    @GetMapping("/me/identity")
    @Operation(summary = "The caller's own identity, read straight from their access token",
            description = "Works WITHOUT tenant context, which is what separates it from GET /me: a platform "
                    + "administrator belongs to no tenant and so has no local user row, and GET /me answers "
                    + "401 TENANT_CONTEXT_MISSING for them by design. They still need to see who they are "
                    + "signed in as, and where to go to change their name or password - Keycloak owns both, "
                    + "since there is no local profile to edit. Same source as "
                    + "/me/platform-roles: the caller's own validated JWT, never a Keycloak admin lookup.")
    public CurrentIdentityResponse getMyIdentity(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        return new CurrentIdentityResponse(
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name"),
                // The realm's token carries the combined "name" claim but not
                // always given_name/family_name, so this is often the only
                // human-readable name available. Deliberately NOT split into
                // first/last here: there is no reliable way to do that across
                // naming conventions, and guessing wrong is worse than
                // showing the name as its owner wrote it.
                jwt.getClaimAsString("name"),
                authorizationService.myPlatformRoles());
    }

    @PutMapping("/me/identity")
    @Operation(summary = "Change the caller's own name and email, in Keycloak and locally",
            description = "Works WITHOUT tenant context, unlike PUT /me - a platform administrator has no "
                    + "local user row to update, only a Keycloak account. Keycloak owns the identity and is "
                    + "written first; the local users row, when there is one, is updated as a mirror. "
                    + "Deliberately cannot change tenant, roles, status or the MFA flag.")
    public CurrentIdentityResponse updateMyIdentity(JwtAuthenticationToken auth,
                                                    @Valid @RequestBody UpdateOwnIdentityRequest request) {
        Jwt jwt = auth.getToken();
        accountSelfService.updateIdentity(requireUuidClaim(jwt, jwt.getSubject(), "sub"), request);
        // Read back from the request rather than the (now stale) token: the
        // caller's access token still carries the old name until it is refreshed.
        return new CurrentIdentityResponse(request.email(), jwt.getClaimAsString("preferred_username"),
                request.firstName(), request.lastName(),
                (request.firstName() + " " + (request.lastName() == null ? "" : request.lastName())).trim(),
                authorizationService.myPlatformRoles());
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change the caller's own password",
            description = "The current password is re-checked against Keycloak before anything is written - "
                    + "Keycloak's admin API can set a password without it, so that check is what stops a "
                    + "stolen session becoming a permanent account takeover. Accounts with MFA enrolled must "
                    + "also send a current one-time code, because verifying the password means a real token "
                    + "request. Works without tenant context, like /me/identity.")
    public void changeMyPassword(JwtAuthenticationToken auth,
                                 @Valid @RequestBody ChangeOwnPasswordRequest request) {
        Jwt jwt = auth.getToken();
        accountSelfService.changePassword(
                requireUuidClaim(jwt, jwt.getSubject(), "sub"),
                requireClaim(jwt, jwt.getClaimAsString("preferred_username"), "preferred_username"),
                request);
    }

    /**
     * Who the caller is according to their token. Nullable throughout on
     * purpose: every field here is an optional OIDC claim, and a missing
     * name is something the UI should render around, not a 400 - unlike
     * {@code /me/provision}, which genuinely cannot proceed without them.
     */
    public record CurrentIdentityResponse(String email, String username, String firstName, String lastName,
                                          String displayName, List<String> platformRoles) {
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_CREATE)")
    @Operation(summary = "Invite a new user into the caller's tenant")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + response.userId())).body(response);
    }

    @GetMapping("/{userId}")
    // Also readable by anyone who can act on an individual user without
    // being able to edit their profile outright (reset-password/activate/
    // deactivate/delete/assign-role) - otherwise that permission is
    // unusable through the UI, since every one of those actions is a
    // row-level dialog (or, for assign-role, the Edit User form's role
    // picker) on this exact data and there's no other way to pick which
    // user to act on. Deliberately excludes otua.manage-users.create -
    // list-independent, since inviting a new user has nothing to do with
    // browsing existing ones.
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_UPDATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_RESET_PASSWORD, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_ACTIVATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_DEACTIVATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_DELETE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_ASSIGN_ROLE)")
    @Operation(summary = "Get a user by id, within the caller's tenant")
    public UserResponse getUser(@PathVariable UUID userId) {
        return userService.getUser(userId);
    }

    @GetMapping("/{userId}/platform-roles")
    // Same read-widening as getUser() above, same reason - see its comment.
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_UPDATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_RESET_PASSWORD, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_ACTIVATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_DEACTIVATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_DELETE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_ASSIGN_ROLE)")
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

    public record EmailAvailability(boolean available) {
    }

    @GetMapping("/email-availability")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_UPDATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_CREATE)")
    @Operation(summary = "Whether an email is free to give a user",
            description = "The same check a save makes - another user in the tenant, or any account that signs in "
                    + "with it. One answer either way, so it can't tell which.")
    // userId: the user whose email is being changed; left out when inviting a new one.
    public EmailAvailability emailAvailability(@RequestParam("email") String email,
                                               @RequestParam(value = "userId", required = false) UUID userId) {
        return new EmailAvailability(userService.isEmailAvailable(email, userId));
    }

    @GetMapping
    // Same read-widening as getUser() above, same reason - see its comment.
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_UPDATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_RESET_PASSWORD, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_ACTIVATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_DEACTIVATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_DELETE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_ASSIGN_ROLE)")
    @Operation(summary = "List users in the caller's tenant")
    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        return PageResponse.from(userService.listUsers(pageable));
    }

    @PutMapping("/{userId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_UPDATE)")
    @Operation(summary = "Update a user's profile/status")
    public UserResponse updateUser(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(userId, request);
    }

    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_RESET_PASSWORD)")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a password reset email to a user in the caller's tenant. Sets a fresh "
            + "temporary password server-side and emails the user a link to choose their own - no "
            + "password is ever returned by this endpoint.")
    public void resetPassword(@PathVariable UUID userId) {
        userService.resetPassword(userId);
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_DEACTIVATE)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate (soft-delete) a user",
            description = "Requires a non-blank reason - one of the audit event catalogue's high-impact "
                    + "actions that must carry a justification (see AuditActionCatalog's USER_DEACTIVATED entry).")
    public void deactivateUser(@PathVariable UUID userId, @Valid @RequestBody DeactivateUserRequest request) {
        userService.deactivateUser(userId, request.reason());
    }

    @PostMapping("/{userId}/reactivate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_ACTIVATE)")
    @Operation(summary = "Reactivate a previously deactivated (soft-deleted) user",
            description = "Undoes deactivateUser: clears the soft-delete, sets status back to active, and "
                    + "re-enables the Keycloak account. 409 if the user isn't currently deactivated.")
    public UserResponse reactivateUser(@PathVariable UUID userId) {
        return userService.reactivateUser(userId);
    }

    /**
     * Used to be gated on {@code @authz.isTenantAdmin()} alone, deliberately
     * excluded from the granular permission system - a hard delete has no
     * undo (unlike deactivateUser, reversible via reactivateUser), and the
     * permission catalog is itself something a tenant admin can hand to any
     * custom role via Role Permission Configuration, so wiring this to a
     * permission code means trusting that same self-service system with
     * irreversible, cross-system account removal.
     * <p>
     * Now additionally gated on {@code otua.manage-users.delete}, by
     * deliberate choice: this action is meant to be delegable, the same way
     * every other user-management capability here already is. The
     * tenant-admin bypass is kept, not replaced - {@code isTenantAdmin()} is
     * ORed in alongside the permission check, so revoking
     * {@code otua.manage-users.delete} from every role can never lock the
     * tenant admin out of its own irreversible-action safety net. Whoever
     * grants this permission to a custom role is knowingly delegating
     * permanent, unrecoverable account deletion - same trust model as any
     * other {@code otua.manage-users.*} grant, no longer a special case.
     */
    @DeleteMapping("/{userId}/permanent")
    @PreAuthorize("@authz.isTenantAdmin() or @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_DELETE)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Permanently delete a user",
            description = "A genuine hard delete: removes the local profile row and the corresponding "
                    + "Keycloak account outright, unlike deactivateUser (soft-delete, reversible via "
                    + "reactivateUser). There is no undo. Available to the tenant admin unconditionally, "
                    + "and to any role granted otua.manage-users.delete.")
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