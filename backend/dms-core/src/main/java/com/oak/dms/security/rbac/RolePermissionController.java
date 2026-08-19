package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.GrantPermissionRequest;
import com.oak.dms.security.rbac.dto.RolePermissionResponse;
import com.oak.dms.security.rbac.RolePermissionService;
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
 * REST endpoints for granting/listing/revoking permission grants on a role,
 * nested under /roles/{roleId}/permissions.
 */
@RestController
@RequestMapping("/api/v1/roles/{roleId}/permissions")
@RequiredArgsConstructor
@Tag(name = "Role Permissions", description = "Permission grants attached to a role")
public class RolePermissionController {

    private final RolePermissionService rolePermissionService;

    @PostMapping
    @PreAuthorize("@authz.hasPermission('role.manage')")
    @Operation(summary = "Grant a permission code to a role")
    public ResponseEntity<RolePermissionResponse> grantPermission(@PathVariable UUID roleId,
                                                                    @Valid @RequestBody GrantPermissionRequest request) {
        RolePermissionResponse response = rolePermissionService.grantPermission(roleId, request);
        return ResponseEntity.created(
                URI.create("/api/v1/roles/" + roleId + "/permissions/" + response.rolePermissionId())
        ).body(response);
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission('role.view') or @authz.hasPermission('role.manage')")
    @Operation(summary = "List permissions granted to a role")
    public List<RolePermissionResponse> listPermissions(@PathVariable UUID roleId) {
        return rolePermissionService.listPermissionsForRole(roleId);
    }

    @DeleteMapping("/{rolePermissionId}")
    @PreAuthorize("@authz.hasPermission('role.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a permission grant from a role")
    public void revokePermission(@PathVariable UUID roleId, @PathVariable UUID rolePermissionId) {
        rolePermissionService.revokePermission(roleId, rolePermissionId);
    }
}
