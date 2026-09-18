package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.AssignRoleRequest;
import com.oak.dms.security.rbac.dto.UserRoleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for assigning/listing/revoking a user's role assignments,
 * nested under /users/{userId}/roles. Supports optional scoping narrower
 * than the whole tenant (department/folder/document) via
 * {@link com.oak.dms.security.rbac.dto.AssignRoleRequest}.
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/roles")
@RequiredArgsConstructor
@Tag(name = "User Roles", description = "Role assignments held by a user, optionally scoped")
public class UserRoleController {

    private final UserRoleService userRoleService;

    @PostMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_ASSIGN_ROLE)")
    @Operation(summary = "Assign a role to a user, optionally scoped to a department/folder/document")
    public ResponseEntity<UserRoleResponse> assignRole(@PathVariable UUID userId,
                                                         @Valid @RequestBody AssignRoleRequest request) {
        UserRoleResponse response = userRoleService.assignRole(userId, request);
        return ResponseEntity.created(
                URI.create("/api/v1/users/" + userId + "/roles/" + response.userRoleId())
        ).body(response);
    }

    // Same read-widening as UserController#getUser, same reason: this is
    // also called per-row while rendering the User List table, which
    // reset-password/activate/deactivate/delete/assign-role-only callers
    // need to reach too - see that method's comment. assign-role needs
    // this one specifically to see a user's *current* assignments before
    // the Edit User form's role picker can offer anything to add/remove.
    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_VIEW, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_UPDATE, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_RESET_PASSWORD, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_ACTIVATE, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_DEACTIVATE, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_DELETE, T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_ASSIGN_ROLE)")
    @Operation(summary = "List a user's role assignments")
    public List<UserRoleResponse> listRoles(@PathVariable UUID userId) {
        return userRoleService.listRolesForUser(userId);
    }

    @DeleteMapping("/{userRoleId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_ASSIGN_ROLE)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a role assignment from a user")
    public void revokeRole(@PathVariable UUID userId, @PathVariable UUID userRoleId) {
        userRoleService.revokeRole(userId, userRoleId);
    }
}
