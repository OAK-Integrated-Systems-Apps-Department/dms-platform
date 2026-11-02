package com.oak.dms.workflow.dto;

import java.util.UUID;

/**
 * One active template a submitter could pick for a document of a given type -
 * deliberately smaller than {@link WorkflowTemplateSummaryResponse} (no stage
 * count/timestamps), since this only backs a submit-time dropdown, not the
 * admin template list.
 */
public record WorkflowTemplateOptionResponse(UUID templateId, String name, String code, int versionNumber) {
}
