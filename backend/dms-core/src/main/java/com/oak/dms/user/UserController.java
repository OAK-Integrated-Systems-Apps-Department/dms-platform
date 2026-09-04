package com.oak.dms.user;

import com.oak.dms.common.exception.ApiException;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile management, backed by Keycloak for authentication")
public class UserController {

    private final UserService userService;

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

    @PostMapping
    // @PreAuthorize("@authz.hasPermission('user.manage')")
    @Operation(summary = "Invite a new user into the caller's tenant")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + response.userId())).body(response);
    }

    @GetMapping("/{userId}")
    // @PreAuthorize("@authz.hasPermission('user.view') or @authz.hasPermission('user.manage')")
    @Operation(summary = "Get a user by id, within the caller's tenant")
    public UserResponse getUser(@PathVariable UUID userId) {
        return userService.getUser(userId);
    }

    @GetMapping
    // @PreAuthorize("@authz.hasPermission('user.view') or @authz.hasPermission('user.manage')")
    @Operation(summary = "List users in the caller's tenant")
    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        return PageResponse.from(userService.listUsers(pageable));
    }

    @PutMapping("/{userId}")
    // @PreAuthorize("@authz.hasPermission('user.manage')")
    @Operation(summary = "Update a user's profile/status")
    public UserResponse updateUser(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(userId, request);
    }

    @PostMapping("/{userId}/reset-password")
    // @PreAuthorize("@authz.hasPermission('user.manage')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a password reset email to a user in the caller's tenant. Sets a fresh "
            + "temporary password server-side and emails the user a link to choose their own - no "
            + "password is ever returned by this endpoint.")
    public void resetPassword(@PathVariable UUID userId) {
        userService.resetPassword(userId);
    }

    @DeleteMapping("/{userId}")
    // @PreAuthorize("@authz.hasPermission('user.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate (soft-delete) a user")
    public void deactivateUser(@PathVariable UUID userId) {
        userService.deactivateUser(userId);
    }

    @PostMapping("/{userId}/reactivate")
    // @PreAuthorize("@authz.hasPermission('user.manage')")
    @Operation(summary = "Reactivate a previously deactivated (soft-deleted) user",
            description = "Undoes deactivateUser: clears the soft-delete, sets status back to active, and "
                    + "re-enables the Keycloak account. 409 if the user isn't currently deactivated.")
    public UserResponse reactivateUser(@PathVariable UUID userId) {
        return userService.reactivateUser(userId);
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