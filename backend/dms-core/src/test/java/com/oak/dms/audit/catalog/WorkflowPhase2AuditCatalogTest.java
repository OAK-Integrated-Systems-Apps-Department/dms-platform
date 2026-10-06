package com.oak.dms.audit.catalog;

import com.oak.dms.audit.enums.AuditSeverity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every Phase 2 action is registered with the module, severity and reason rule of docs/events/workflow-audit-events.md. */
class WorkflowPhase2AuditCatalogTest {

    private record Expected(String module, AuditSeverity severity, boolean reasonRequired) {
    }

    @Test
    void phase2ActionsAreRegistered() {
        Map<String, Expected> expected = Map.ofEntries(
                Map.entry(AuditActionCatalog.WORKFLOW_TASK_DELEGATED, new Expected("WA", AuditSeverity.MEDIUM, false)),
                Map.entry(AuditActionCatalog.WORKFLOW_TASK_REASSIGNED, new Expected("WA", AuditSeverity.MEDIUM, true)),
                Map.entry(AuditActionCatalog.WORKFLOW_TASK_REMINDER_SENT, new Expected("WA", AuditSeverity.LOW, false)),
                Map.entry(AuditActionCatalog.WORKFLOW_TASK_ESCALATED, new Expected("WA", AuditSeverity.MEDIUM, false)),
                Map.entry(AuditActionCatalog.WORKFLOW_INFO_REQUESTED, new Expected("WA", AuditSeverity.MEDIUM, false)),
                Map.entry(AuditActionCatalog.WORKFLOW_INFO_PROVIDED, new Expected("WA", AuditSeverity.MEDIUM, false)),
                Map.entry(AuditActionCatalog.WORKFLOW_INFO_DECLINED, new Expected("WA", AuditSeverity.MEDIUM, true)),
                Map.entry(AuditActionCatalog.WORKFLOW_INFO_CANCELLED, new Expected("WA", AuditSeverity.MEDIUM, true)),
                Map.entry(AuditActionCatalog.WORKFLOW_INFO_REQUEST_REASSIGNED, new Expected("WA", AuditSeverity.LOW, false)),
                Map.entry(AuditActionCatalog.WORKFLOW_SUPPORTING_DOCUMENT_ADDED, new Expected("WA", AuditSeverity.LOW, false)),
                Map.entry(AuditActionCatalog.WORKFLOW_SUPPORTING_DOCUMENT_REMOVED, new Expected("WA", AuditSeverity.LOW, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_COMMENT_CREATED, new Expected("DOCM", AuditSeverity.LOW, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_COMMENT_UPDATED, new Expected("DOCM", AuditSeverity.LOW, false)),
                Map.entry(AuditActionCatalog.DOCUMENT_COMMENT_DELETED, new Expected("DOCM", AuditSeverity.MEDIUM, false)));
        expected.forEach((action, e) -> {
            AuditActionSpec spec = AuditActionCatalog.require(action);
            assertThat(spec.module()).as(action).isEqualTo(e.module());
            assertThat(spec.severity()).as(action).isEqualTo(e.severity());
            assertThat(spec.reasonRequired()).as(action).isEqualTo(e.reasonRequired());
        });
    }
}
