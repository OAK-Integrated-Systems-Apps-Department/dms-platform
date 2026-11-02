package com.oak.dms.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * templateId is optional when the document's type has exactly one active
 * workflow template (resolved automatically), required when it has more than
 * one - see {@code TemplateSelector#selectFor}. supportingDocuments is
 * optional: evidence attached in the same transaction as the submission
 * (W18a). Each follows the normal supporting-document rules (you can see it,
 * it isn't the document being submitted, its version is clean) and an
 * invalid one fails the whole submission - nothing is created.
 */
public record SubmitWorkflowRequest(@NotNull UUID documentId,
                                    UUID templateId,
                                    @Valid List<SupportingDocumentInput> supportingDocuments) {
}
