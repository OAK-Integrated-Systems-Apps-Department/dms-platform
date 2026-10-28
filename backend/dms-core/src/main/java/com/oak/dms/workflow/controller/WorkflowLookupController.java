package com.oak.dms.workflow.controller;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.workflow.dto.LookupOptionResponse;
import com.oak.dms.workflow.repository.WorkflowLookupRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pick lists for the stage builder and the department-head select: active
 * users, roles and document types of the caller's tenant, names only. Open
 * to template designers and department managers, who otherwise would need
 * the full user/role/document-type admin permissions just to fill a picker.
 */
@RestController
@RequestMapping("/api/v1/workflow/lookups")
@RequiredArgsConstructor
@PreAuthorize("@authz.hasAnyPermission("
        + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_CREATE, "
        + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_UPDATE, "
        + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_CREATE, "
        + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_DEPARTMENTS_UPDATE)")
@Tag(name = "Workflow templates")
public class WorkflowLookupController {

    private final WorkflowLookupRepository lookupRepository;

    @GetMapping("/users")
    @Transactional(readOnly = true)
    @Operation(summary = "Active users (id, name, email)")
    public List<LookupOptionResponse> users() {
        return lookupRepository.activeUsers(TenantContext.getTenantId());
    }

    @GetMapping("/roles")
    @Transactional(readOnly = true)
    @Operation(summary = "Active roles (id, name)")
    public List<LookupOptionResponse> roles() {
        return lookupRepository.activeRoles(TenantContext.getTenantId());
    }

    @GetMapping("/document-types")
    @Transactional(readOnly = true)
    @Operation(summary = "Active document types (id, name)")
    public List<LookupOptionResponse> documentTypes() {
        return lookupRepository.activeDocumentTypes(TenantContext.getTenantId());
    }
}
