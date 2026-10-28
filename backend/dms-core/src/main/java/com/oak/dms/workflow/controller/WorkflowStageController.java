package com.oak.dms.workflow.controller;

import com.oak.dms.workflow.dto.ReorderStagesRequest;
import com.oak.dms.workflow.dto.UpsertWorkflowStageRequest;
import com.oak.dms.workflow.dto.WorkflowTemplateResponse;
import com.oak.dms.workflow.service.WorkflowTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Stage configuration of a draft template (W4). Every call returns the whole
 * template so the stage builder can re-render from one response. Approvers
 * are stored as entries (user/role/department) and only resolved to people
 * when a stage activates.
 */
@RestController
@RequestMapping("/api/v1/workflow/templates/{templateId}/stages")
@RequiredArgsConstructor
@PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TEMPLATES_UPDATE)")
@Tag(name = "Workflow templates")
public class WorkflowStageController {

    private final WorkflowTemplateService templateService;

    @PostMapping
    @Operation(summary = "Append a stage", description = "422 for an unknown/inactive approver or an invalid SLA/escalation setup.")
    public ResponseEntity<WorkflowTemplateResponse> add(@PathVariable("templateId") UUID templateId,
                                                        @Valid @RequestBody UpsertWorkflowStageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.addStage(templateId, request));
    }

    @PutMapping("/{stageId}")
    @Operation(summary = "Replace a stage's settings and approver list")
    public WorkflowTemplateResponse update(@PathVariable("templateId") UUID templateId,
                                           @PathVariable("stageId") UUID stageId,
                                           @Valid @RequestBody UpsertWorkflowStageRequest request) {
        return templateService.updateStage(templateId, stageId, request);
    }

    @DeleteMapping("/{stageId}")
    @Operation(summary = "Remove a stage", description = "The remaining stages are renumbered 1..n.")
    public WorkflowTemplateResponse delete(@PathVariable("templateId") UUID templateId,
                                           @PathVariable("stageId") UUID stageId) {
        return templateService.deleteStage(templateId, stageId);
    }

    @PutMapping("/order")
    @Operation(summary = "Reorder stages", description = "stageIds must list every stage exactly once.")
    public WorkflowTemplateResponse reorder(@PathVariable("templateId") UUID templateId,
                                            @Valid @RequestBody ReorderStagesRequest request) {
        return templateService.reorderStages(templateId, request);
    }
}
