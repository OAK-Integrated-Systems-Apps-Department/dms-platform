package com.oak.dms.security.rbac;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.security.rbac.dto.CreatePermissionModuleRequest;
import com.oak.dms.security.rbac.dto.PermissionModuleResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionModuleRequest;
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
 * REST endpoints for the platform-wide permission module catalog (the top
 * of the permission_modules -> permission_actions -> permissions
 * hierarchy). Not tenant-scoped (see {@link PermissionModule}), so
 * create/update/delete are a platform-administration concern, matching
 * {@link PermissionController}.
 */
@RestController
@RequestMapping("/api/v1/permission-modules")
@RequiredArgsConstructor
@Tag(name = "Permission Modules", description = "Top-level groupings in the platform-wide permission catalog")
public class PermissionModuleController {

    private final PermissionModuleService permissionModuleService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Add a module to the permission catalog")
    public ResponseEntity<PermissionModuleResponse> createModule(@Valid @RequestBody CreatePermissionModuleRequest request) {
        PermissionModuleResponse response = permissionModuleService.createModule(request);
        return ResponseEntity.created(URI.create("/api/v1/permission-modules/" + response.moduleId())).body(response);
    }

    @GetMapping("/{moduleId}")
    @PreAuthorize("@authz.hasPermission('role.view') or @authz.hasPermission('role.manage')")
    @Operation(summary = "Get a permission module by id")
    public PermissionModuleResponse getModule(@PathVariable UUID moduleId) {
        return permissionModuleService.getModule(moduleId);
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission('role.view') or @authz.hasPermission('role.manage')")
    @Operation(summary = "List the platform's permission modules")
    public PageResponse<PermissionModuleResponse> listModules(Pageable pageable) {
        return PageResponse.from(permissionModuleService.listModules(pageable));
    }

    @PutMapping("/{moduleId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Update a permission module's name/description")
    public PermissionModuleResponse updateModule(@PathVariable UUID moduleId,
                                                  @Valid @RequestBody UpdatePermissionModuleRequest request) {
        return permissionModuleService.updateModule(moduleId, request);
    }

    @DeleteMapping("/{moduleId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete (soft-delete) a permission module from the catalog")
    public void deleteModule(@PathVariable UUID moduleId) {
        permissionModuleService.deleteModule(moduleId);
    }
}
