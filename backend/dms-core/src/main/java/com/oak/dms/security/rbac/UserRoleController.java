package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.AssignRoleRequest;
import com.oak.dms.security.rbac.dto.UserRoleResponse;
import com.oak.dms.security.rbac.UserRoleService;
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
    @PreAuthorize("@authz.hasPermission('user.manage')")
    @Operation(summary = "Assign a role to a user, optionally scoped to a department/folder/document")
    public ResponseEntity<UserRoleResponse> assignRole(@PathVariable UUID userId,
                                                         @Valid @RequestBody AssignRoleRequest request) {
        UserRoleResponse response = userRoleService.assignRole(userId, request);
        return ResponseEntity.created(
                URI.create("/api/v1/users/" + userId + "/roles/" + response.userRoleId())
        ).body(response);
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission('user.view') or @authz.hasPermission('user.manage')")
    @Operation(summary = "List a user's role assignments")
    public List<UserRoleResponse> listRoles(@PathVariable UUID userId) {
        return userRoleService.listRolesForUser(userId);
    }

    @DeleteMapping("/{userRoleId}")
    @PreAuthorize("@authz.hasPermission('user.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a role assignment from a user")
    public void revokeRole(@PathVariable UUID userId, @PathVariable UUID userRoleId) {
        userRoleService.revokeRole(userId, userRoleId);
    }
}
