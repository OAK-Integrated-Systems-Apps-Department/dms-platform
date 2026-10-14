package com.oak.dms.workflow.service;

import com.oak.dms.workflow.dto.CreateWorkflowTemplateRequest;
import com.oak.dms.workflow.dto.ReorderStagesRequest;
import com.oak.dms.workflow.dto.UpdateWorkflowTemplateRequest;
import com.oak.dms.workflow.dto.UpsertWorkflowStageRequest;
import com.oak.dms.workflow.dto.WorkflowTemplateResponse;
import com.oak.dms.workflow.dto.WorkflowTemplateSummaryResponse;
import com.oak.dms.workflow.enums.TemplateStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Template configuration (W3/W4). Only {@code draft} versions are edited;
 * an active or inactive version is changed by creating a new draft version
 * (decision 9), so an in-flight instance never sees its stages change.
 */
public interface WorkflowTemplateService {

    Page<WorkflowTemplateSummaryResponse> list(TemplateStatus status, UUID documentTypeId, String search,
                                               Pageable pageable);

    WorkflowTemplateResponse get(UUID templateId);

    List<WorkflowTemplateSummaryResponse> versions(UUID templateId);

    WorkflowTemplateResponse create(CreateWorkflowTemplateRequest request);

    WorkflowTemplateResponse update(UUID templateId, UpdateWorkflowTemplateRequest request);

    WorkflowTemplateResponse createVersion(UUID templateId);

    WorkflowTemplateResponse activate(UUID templateId);

    WorkflowTemplateResponse deactivate(UUID templateId);

    void delete(UUID templateId);

    WorkflowTemplateResponse addStage(UUID templateId, UpsertWorkflowStageRequest request);

    WorkflowTemplateResponse updateStage(UUID templateId, UUID stageId, UpsertWorkflowStageRequest request);

    WorkflowTemplateResponse deleteStage(UUID templateId, UUID stageId);

    WorkflowTemplateResponse reorderStages(UUID templateId, ReorderStagesRequest request);
}
