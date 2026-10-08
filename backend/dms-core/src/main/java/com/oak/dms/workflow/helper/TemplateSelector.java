package com.oak.dms.workflow.helper;

import com.oak.dms.document.Document;
import com.oak.dms.workflow.entity.WorkflowTemplate;
import com.oak.dms.workflow.enums.TemplateStatus;
import com.oak.dms.workflow.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Which template a document is routed through. Phase 1: the one active
 * template of its document type (decision 7). Condition-based routing
 * (e.g. amount thresholds) is deferred and stays a change to this class only.
 */
@Component
@RequiredArgsConstructor
public class TemplateSelector {

    private final WorkflowTemplateRepository templateRepository;

    public Optional<WorkflowTemplate> selectFor(Document document) {
        return templateRepository.findFirstByTenantIdAndDocumentTypeIdAndStatusAndDeletedFalse(document.getTenantId(),
                document.getDocumentTypeId(), TemplateStatus.active);
    }
}
