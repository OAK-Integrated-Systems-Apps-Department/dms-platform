package com.oak.dms.security.rbac;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.security.rbac.dto.CreatePermissionRequest;
import com.oak.dms.security.rbac.dto.PermissionResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionRequest;
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
 * REST endpoints for the platform-wide permission catalog. Unlike roles,
 * permissions are not tenant-scoped (see {@link Permission}), so
 * create/update/delete are a platform-administration concern restricted to
 * PLATFORM_ADMIN, matching how {@code TenantController} gates tenant
 * lifecycle. Any caller who can view or manage roles can browse the catalog
 * in order to build grants via {@code RolePermissionController}.
 */
@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
@Tag(name = "Permissions", description = "Platform-wide permission catalog")
public class PermissionController {

    private final PermissionService permissionService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Add a permission to the platform catalog")
    public ResponseEntity<PermissionResponse> createPermission(@Valid @RequestBody CreatePermissionRequest request) {
        PermissionResponse response = permissionService.createPermission(request);
        return ResponseEntity.created(URI.create("/api/v1/permissions/" + response.permissionId())).body(response);
    }

    @GetMapping("/{permissionId}")
    @PreAuthorize("@authz.hasPermission('role.view') or @authz.hasPermission('role.manage')")
    @Operation(summary = "Get a permission by id")
    public PermissionResponse getPermission(@PathVariable UUID permissionId) {
        return permissionService.getPermission(permissionId);
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission('role.view') or @authz.hasPermission('role.manage')")
    @Operation(summary = "List the platform's permission catalog")
    public PageResponse<PermissionResponse> listPermissions(Pageable pageable) {
        return PageResponse.from(permissionService.listPermissions(pageable));
    }

    @PutMapping("/{permissionId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Update a permission's name/description/action")
    public PermissionResponse updatePermission(@PathVariable UUID permissionId,
                                                @Valid @RequestBody UpdatePermissionRequest request) {
        return permissionService.updatePermission(permissionId, request);
    }

    @DeleteMapping("/{permissionId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete (soft-delete) a permission from the catalog")
    public void deletePermission(@PathVariable UUID permissionId) {
        permissionService.deletePermission(permissionId);
    }
}
