package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.CreateRoleRequest;
import com.oak.dms.security.rbac.dto.UpdateRoleRequest;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.security.rbac.dto.RoleResponse;
import com.oak.dms.security.rbac.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * REST endpoints for tenant-scoped role definitions. All operations are
 * gated by role.view / role.manage permission codes, checked against the
 * caller's own role_permissions grants.
 */
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
@Tag(name = "Roles", description = "Tenant-scoped role definitions")
public class RoleController {

    private final RoleService roleService;

    @PostMapping
    @PreAuthorize("@authz.hasPermission('role.manage')")
    @Operation(summary = "Create a role in the caller's tenant")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        RoleResponse response = roleService.createRole(request);
        return ResponseEntity.created(URI.create("/api/v1/roles/" + response.roleId())).body(response);
    }

    @GetMapping("/{roleId}")
    @PreAuthorize("@authz.hasPermission('role.view') or @authz.hasPermission('role.manage')")
    @Operation(summary = "Get a role by id")
    public RoleResponse getRole(@PathVariable UUID roleId) {
        return roleService.getRole(roleId);
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission('role.view') or @authz.hasPermission('role.manage')")
    @Operation(summary = "List roles in the caller's tenant")
    public PageResponse<RoleResponse> listRoles(Pageable pageable) {
        return PageResponse.from(roleService.listRoles(pageable));
    }

    @PutMapping("/{roleId}")
    @PreAuthorize("@authz.hasPermission('role.manage')")
    @Operation(summary = "Update a role's name/description")
    public RoleResponse updateRole(@PathVariable UUID roleId, @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.updateRole(roleId, request);
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("@authz.hasPermission('role.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete (soft-delete) a role")
    public void deleteRole(@PathVariable UUID roleId) {
        roleService.deleteRole(roleId);
    }
}
