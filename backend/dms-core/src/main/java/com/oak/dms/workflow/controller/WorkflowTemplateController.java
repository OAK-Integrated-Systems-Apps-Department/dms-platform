package com.oak.dms.workflow.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.workflow.dto.CreateWorkflowTemplateRequest;
import com.oak.dms.workflow.dto.UpdateWorkflowTemplateRequest;
import com.oak.dms.workflow.dto.WorkflowTemplateOptionResponse;
import com.oak.dms.workflow.dto.WorkflowTemplateResponse;
import com.oak.dms.workflow.dto.WorkflowTemplateSummaryResponse;
import com.oak.dms.workflow.enums.TemplateStatus;
import com.oak.dms.workflow.service.WorkflowTemplateService;
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
 * Workflow template versions (W3). Only drafts are editable; activating a
 * version makes it one of the templates new submissions of its document type
 * can use - a document type may have more than one active template (decision
 * 7 superseded, V121), in which case the submitter picks among them (see
 * {@link #activeFor}).
 */
@RestController
@RequestMapping("/api/v1/workflow/templates")
@RequiredArgsConstructor
@Tag(name = "Workflow templates", description = "Approval chain configuration per document type")
public class WorkflowTemplateController {

    static final String ANY_TEMPLATE_PERMISSION = "@authz.hasAnyPermission("
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_CREATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_UPDATE, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_DELETE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_TOGGLE_ACTIVE)";

    private final WorkflowTemplateService templateService;

    @GetMapping
    @PreAuthorize(ANY_TEMPLATE_PERMISSION)
    @Operation(summary = "List template versions", description = "Optional filters: status, documentTypeId, search (name or code).")
    public PageResponse<WorkflowTemplateSummaryResponse> list(
            @RequestParam(required = false) TemplateStatus status,
            @RequestParam(required = false) UUID documentTypeId,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(templateService.list(status, documentTypeId, search, pageable));
    }

    @GetMapping("/active")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DLM_SUBMIT_DOCUMENTS_FOR_REVIEW)")
    @Operation(summary = "Active templates for one document type",
            description = "Backs the \"which workflow\" choice when submitting a document for approval - unlike "
                    + "list(), reachable by anyone who can submit documents, not just template admins.")
    public List<WorkflowTemplateOptionResponse> activeFor(@RequestParam UUID documentTypeId) {
        return templateService.listActiveForDocumentType(documentTypeId);
    }

    @GetMapping("/{id}")
    @PreAuthorize(ANY_TEMPLATE_PERMISSION)
    @Operation(summary = "Get a template version with its stages and approvers")
    public WorkflowTemplateResponse get(@PathVariable("id") UUID id) {
        return templateService.get(id);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize(ANY_TEMPLATE_PERMISSION)
    @Operation(summary = "Every version of this template (same code), newest first")
    public List<WorkflowTemplateSummaryResponse> versions(@PathVariable("id") UUID id) {
        return templateService.versions(id);
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_CREATE)")
    @Operation(summary = "Create a template (version 1, draft)", description = "409 WORKFLOW_TEMPLATE_CODE_EXISTS if the code is taken.")
    public ResponseEntity<WorkflowTemplateResponse> create(@Valid @RequestBody CreateWorkflowTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_UPDATE)")
    @Operation(summary = "Update a draft's details", description = "409 WORKFLOW_TEMPLATE_NOT_DRAFT for an active/inactive version.")
    public WorkflowTemplateResponse update(@PathVariable("id") UUID id,
                                           @Valid @RequestBody UpdateWorkflowTemplateRequest request) {
        return templateService.update(id, request);
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_UPDATE)")
    @Operation(summary = "Create the next version as a draft copy of this one",
            description = "409 WORKFLOW_DRAFT_EXISTS if the template already has a draft version.")
    public ResponseEntity<WorkflowTemplateResponse> createVersion(@PathVariable("id") UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.createVersion(id));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_TOGGLE_ACTIVE)")
    @Operation(summary = "Make this version the active template for its document type",
            description = "Deactivates the previously active template of that type. 422 if a stage has no approvers "
                    + "or an approver no longer exists.")
    public WorkflowTemplateResponse activate(@PathVariable("id") UUID id) {
        return templateService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_TOGGLE_ACTIVE)")
    @Operation(summary = "Stop new submissions using this version", description = "In-flight workflows continue.")
    public WorkflowTemplateResponse deactivate(@PathVariable("id") UUID id) {
        return templateService.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_DELETE)")
    @Operation(summary = "Delete a draft or inactive version that no workflow has run on",
            description = "409 WORKFLOW_TEMPLATE_IN_USE otherwise.")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
