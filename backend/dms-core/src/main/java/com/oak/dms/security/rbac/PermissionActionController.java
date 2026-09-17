package com.oak.dms.security.rbac;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.security.rbac.dto.CreatePermissionActionRequest;
import com.oak.dms.security.rbac.dto.PermissionActionResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionActionRequest;
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
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for the platform-wide permission action catalog (the
 * middle of the permission_modules -> permission_actions -> permissions
 * hierarchy). Not tenant-scoped (see {@link PermissionModule}), so
 * create/update/delete are a platform-administration concern, matching
 * {@link PermissionController}.
 */
@RestController
@RequestMapping("/api/v1/permission-actions")
@RequiredArgsConstructor
@Tag(name = "Permission Actions", description = "Verbs available under each permission module")
public class PermissionActionController {

    private final PermissionActionService permissionActionService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Add an action to a permission module")
    public ResponseEntity<PermissionActionResponse> createAction(@Valid @RequestBody CreatePermissionActionRequest request) {
        PermissionActionResponse response = permissionActionService.createAction(request);
        return ResponseEntity.created(URI.create("/api/v1/permission-actions/" + response.actionId())).body(response);
    }

    @GetMapping("/{actionId}")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).ASM_ROLES_VIEW_ROLE, T(com.oak.dms.security.rbac.PermissionCodes).ASM_ROLES_ASSIGN_PERMISSIONS)")
    @Operation(summary = "Get a permission action by id")
    public PermissionActionResponse getAction(@PathVariable UUID actionId) {
        return permissionActionService.getAction(actionId);
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).ASM_ROLES_VIEW_ROLE, T(com.oak.dms.security.rbac.PermissionCodes).ASM_ROLES_ASSIGN_PERMISSIONS)")
    @Operation(summary = "List the platform's permission actions")
    public PageResponse<PermissionActionResponse> listActions(Pageable pageable) {
        return PageResponse.from(permissionActionService.listActions(pageable));
    }

    @GetMapping("/by-module/{moduleId}")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).ASM_ROLES_VIEW_ROLE, T(com.oak.dms.security.rbac.PermissionCodes).ASM_ROLES_ASSIGN_PERMISSIONS)")
    @Operation(summary = "List all actions under a single permission module")
    public List<PermissionActionResponse> listActionsForModule(@PathVariable UUID moduleId) {
        return permissionActionService.listActionsForModule(moduleId);
    }

    @PutMapping("/{actionId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Update a permission action's name/description")
    public PermissionActionResponse updateAction(@PathVariable UUID actionId,
                                                  @Valid @RequestBody UpdatePermissionActionRequest request) {
        return permissionActionService.updateAction(actionId, request);
    }

    @DeleteMapping("/{actionId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete (soft-delete) a permission action from the catalog")
    public void deleteAction(@PathVariable UUID actionId) {
        permissionActionService.deleteAction(actionId);
    }
}
