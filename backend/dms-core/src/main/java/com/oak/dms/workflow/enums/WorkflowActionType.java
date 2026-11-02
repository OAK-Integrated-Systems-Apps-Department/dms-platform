package com.oak.dms.workflow.enums;

/**
 * workflow_actions.action_type (V45, widened by V48) - the UI timeline.
 */
public enum WorkflowActionType {
    submitted, claimed, released, approved, rejected, retracted,
    stage_activated, stage_approved, stage_rejected, stage_unassignable,
    workflow_approved, workflow_rejected, cancelled, version_changed, restarted,
    reassigned, delegated, escalated, reminded, commented,
    info_requested, info_provided, info_declined, info_cancelled,
    info_reassigned, supporting_document_added, supporting_document_removed
}
