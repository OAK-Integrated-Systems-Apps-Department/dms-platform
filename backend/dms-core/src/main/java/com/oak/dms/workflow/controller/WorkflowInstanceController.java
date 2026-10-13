package com.oak.dms.workflow.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.workflow.dto.CancelWorkflowRequest;
import com.oak.dms.workflow.dto.DocumentWorkflowStatusResponse;
import com.oak.dms.workflow.dto.SubmitWorkflowRequest;
import com.oak.dms.workflow.dto.SupportingDocumentInput;
import com.oak.dms.workflow.dto.SupportingDocumentView;
import com.oak.dms.workflow.dto.WorkflowActionView;
import com.oak.dms.workflow.dto.WorkflowInstanceResponse;
import com.oak.dms.workflow.dto.WorkflowInstanceSummaryResponse;
import com.oak.dms.workflow.service.WorkflowQueryService;
import com.oak.dms.workflow.service.WorkflowSubmissionService;
import com.oak.dms.workflow.service.WorkflowSupportingDocumentService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Submitting documents into their workflow (W5) and following it (W8):
 * status with stages and tasks, the action timeline, a document's history.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Workflow instances", description = "Submit documents for approval and track their status")
public class WorkflowInstanceController {

    private static final int MAX_STATUS_DOCUMENTS = 200;

    private final WorkflowSubmissionService submissionService;
    private final WorkflowQueryService queryService;
    private final WorkflowSupportingDocumentService supportingDocumentService;

    @PostMapping("/workflow/instances")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DLM_SUBMIT_DOCUMENTS_FOR_REVIEW)")
    @Operation(summary = "Submit a document into its document type's active workflow",
            description = "All-or-nothing. 409 WORKFLOW_ALREADY_IN_PROGRESS / WORKFLOW_DOCUMENT_NOT_ELIGIBLE; "
                    + "422 WORKFLOW_NO_ACTIVE_TEMPLATE / WORKFLOW_NO_ELIGIBLE_APPROVERS / WORKFLOW_INITIATOR_IS_APPROVER.")
    public ResponseEntity<WorkflowInstanceResponse> submit(@Valid @RequestBody SubmitWorkflowRequest request) {
        UUID instanceId = submissionService.submit(request.documentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(queryService.getInstance(instanceId));
    }

    @GetMapping("/workflow/instances/{id}")
    @Operation(summary = "Status of a workflow: stages, tasks, current assignees",
            description = "Visible to participants, document viewers and audit-trail viewers; 403 otherwise.")
    public WorkflowInstanceResponse get(@PathVariable("id") UUID id) {
        return queryService.getInstance(id);
    }

    @GetMapping("/workflow/instances/{id}/actions")
    @Operation(summary = "The workflow's timeline, oldest first")
    public PageResponse<WorkflowActionView> timeline(@PathVariable("id") UUID id,
            @PageableDefault(size = 50, sort = "occurredAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(queryService.timeline(id, pageable));
    }

    @PostMapping("/workflow/instances/{id}/cancel")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DLM_SUBMIT_DOCUMENTS_FOR_REVIEW) or @authz.isTenantAdmin()")
    @Operation(summary = "Withdraw an in-progress workflow (its initiator, or a tenant admin)",
            description = "A reason is required.")
    public WorkflowInstanceResponse cancel(@PathVariable("id") UUID id, @Valid @RequestBody CancelWorkflowRequest request) {
        submissionService.cancel(id, request.reason());
        return queryService.getInstance(id);
    }

    @GetMapping("/documents/{documentId}/workflow-instances")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).DLM_SUBMIT_DOCUMENTS_FOR_REVIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_AUDIT_TRAIL_VIEW)")
    @Operation(summary = "A document's workflow history, newest first")
    public PageResponse<WorkflowInstanceSummaryResponse> documentHistory(@PathVariable("documentId") UUID documentId,
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(queryService.documentHistory(documentId, pageable));
    }

    @GetMapping("/workflow/document-status")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).DLM_SUBMIT_DOCUMENTS_FOR_REVIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_AUDIT_TRAIL_VIEW)")
    @Operation(summary = "The newest workflow run of each of these documents, for a list of documents",
            description = "documentIds: at most 200 per call. Documents the caller can't open, and documents never "
                    + "submitted, are left out.")
    public List<DocumentWorkflowStatusResponse> documentStatuses(@RequestParam("documentIds") List<UUID> documentIds) {
        return queryService.documentStatuses(documentIds.size() > MAX_STATUS_DOCUMENTS
                ? documentIds.subList(0, MAX_STATUS_DOCUMENTS) : documentIds);
    }

    @PostMapping("/workflow/instances/{id}/supporting-documents")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Attach a document as evidence (link an existing one, or one you just uploaded)",
            description = "Pinned to a version (default: the latest). Participants only. Never routed for approval "
                    + "and never part of the version-change check.")
    public ResponseEntity<SupportingDocumentView> addSupportingDocument(@PathVariable("id") UUID id,
            @Valid @RequestBody SupportingDocumentInput request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supportingDocumentService.add(id, request));
    }

    @GetMapping("/workflow/instances/{id}/supporting-documents")
    @Operation(summary = "Supporting documents of a workflow run",
            description = "canOpen is false for a viewer without docm.documents.view - they see title and note only.")
    public List<SupportingDocumentView> supportingDocuments(@PathVariable("id") UUID id) {
        return supportingDocumentService.list(id);
    }

    @DeleteMapping("/workflow/instances/{id}/supporting-documents/{supportingDocumentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a supporting document I attached")
    public void removeSupportingDocument(@PathVariable("id") UUID id,
                                         @PathVariable("supportingDocumentId") UUID supportingDocumentId) {
        supportingDocumentService.remove(id, supportingDocumentId);
    }
}
