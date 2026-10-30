package com.oak.dms.workflow.helper;

import com.oak.dms.document.Document;
import com.oak.dms.workflow.entity.WorkflowTemplate;
import com.oak.dms.workflow.enums.TemplateStatus;
import com.oak.dms.workflow.exception.WorkflowValidationException;
import com.oak.dms.workflow.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which template a document is routed through. A document type can have more
 * than one active template (decision 7 superseded, V120): with exactly one,
 * it's picked automatically as before; with more than one, the submitter
 * must say which (see {@code WorkflowSubmissionServiceImpl#submit}). Condition-
 * based routing (e.g. amount thresholds) is deferred and stays a change to
 * this class only.
 */
@Component
@RequiredArgsConstructor
public class TemplateSelector {

    private final WorkflowTemplateRepository templateRepository;

    /** Every active template of this document's type - the candidates a submitter could pick from. */
    public List<WorkflowTemplate> candidatesFor(Document document) {
        return templateRepository.findByTenantIdAndDocumentTypeIdAndStatusAndDeletedFalse(document.getTenantId(),
                document.getDocumentTypeId(), TemplateStatus.active);
    }

    /**
     * Resolves the template a submission should use.
     * {@code requestedTemplateId} null and exactly one active candidate -> that one, same as before V120.
     * {@code requestedTemplateId} null and more than one candidate -> WORKFLOW_TEMPLATE_SELECTION_REQUIRED.
     * {@code requestedTemplateId} null and no candidates -> WORKFLOW_NO_ACTIVE_TEMPLATE.
     * {@code requestedTemplateId} given -> it must be one of the active candidates, else WORKFLOW_TEMPLATE_INVALID_SELECTION
     * (re-checked here rather than trusted from a possibly-stale frontend dropdown).
     */
    public WorkflowTemplate selectFor(Document document, UUID requestedTemplateId) {
        List<WorkflowTemplate> candidates = candidatesFor(document);
        if (requestedTemplateId != null) {
            return candidates.stream()
                    .filter(t -> t.getTemplateId().equals(requestedTemplateId))
                    .findFirst()
                    .orElseThrow(() -> new WorkflowValidationException("WORKFLOW_TEMPLATE_INVALID_SELECTION",
                            "That workflow template isn't active for this document's type."));
        }
        if (candidates.isEmpty()) {
            throw new WorkflowValidationException("WORKFLOW_NO_ACTIVE_TEMPLATE",
                    "No active workflow template is configured for this document's type");
        }
        if (candidates.size() > 1) {
            String names = candidates.stream().map(WorkflowTemplate::getName).collect(Collectors.joining(", "));
            throw new WorkflowValidationException("WORKFLOW_TEMPLATE_SELECTION_REQUIRED",
                    "More than one active workflow template exists for this document type - choose one: " + names);
        }
        return candidates.get(0);
    }
}
