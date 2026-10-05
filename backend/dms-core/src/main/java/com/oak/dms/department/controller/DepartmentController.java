package com.oak.dms.department.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.department.dto.AssignUserDepartmentRequest;
import com.oak.dms.department.dto.CreateDepartmentRequest;
import com.oak.dms.department.dto.DepartmentMemberResponse;
import com.oak.dms.department.dto.DepartmentResponse;
import com.oak.dms.department.dto.UpdateDepartmentRequest;
import com.oak.dms.department.dto.UserDepartmentResponse;
import com.oak.dms.department.entity.Department;
import com.oak.dms.department.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Departments of the caller's tenant (V43) and each user's primary
 * department. Reading is also open to workflow template designers, who pick
 * departments as approver pools in the stage builder.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Departments", description = "Departments and users' primary department")
public class DepartmentController {

    private static final String READ = "@authz.hasAnyPermission("
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_VIEW, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_UPDATE, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_CREATE, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_UPDATE)";

    private final DepartmentService departmentService;

    @GetMapping("/departments")
    @PreAuthorize(READ)
    @Operation(summary = "List departments", description = "status and search (name or code) are optional filters.")
    public PageResponse<DepartmentResponse> list(
            @RequestParam(required = false) Department.DepartmentStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(departmentService.list(status, search, pageable));
    }

    @GetMapping("/departments/{id}")
    @PreAuthorize(READ)
    @Operation(summary = "Get a department")
    public DepartmentResponse get(@PathVariable("id") UUID id) {
        return departmentService.get(id);
    }

    @GetMapping("/departments/{id}/members")
    @PreAuthorize(READ)
    @Operation(summary = "List a department's direct members (its approver pool)")
    public List<DepartmentMemberResponse> members(@PathVariable("id") UUID id) {
        return departmentService.members(id);
    }

    @PostMapping("/departments")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_CREATE)")
    @Operation(summary = "Create a department", description = "409 DEPARTMENT_CODE_EXISTS if the code is taken.")
    public ResponseEntity<DepartmentResponse> create(@Valid @RequestBody CreateDepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(departmentService.create(request));
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_UPDATE)")
    @Operation(summary = "Update a department", description = "409 DEPARTMENT_CYCLE if the new parent is a descendant.")
    public DepartmentResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateDepartmentRequest request) {
        return departmentService.update(id, request);
    }

    @DeleteMapping("/departments/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_DELETE)")
    @Operation(summary = "Delete a department",
            description = "Soft delete. 409 while it has members, child departments, or a workflow stage routing to it.")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        departmentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/department")
    @PreAuthorize("@authz.hasAnyPermission("
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_VIEW, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_UPDATE, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_VIEW)")
    @Operation(summary = "Get a user's primary department")
    public UserDepartmentResponse getUserDepartment(@PathVariable("userId") UUID userId) {
        return departmentService.getUserDepartment(userId);
    }

    @PutMapping("/users/{userId}/department")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_UPDATE)")
    @Operation(summary = "Set or clear a user's primary department", description = "departmentId null clears it.")
    public UserDepartmentResponse assignUserDepartment(@PathVariable("userId") UUID userId,
                                                       @RequestBody AssignUserDepartmentRequest request) {
        return departmentService.assignUserDepartment(userId, request);
    }
}
