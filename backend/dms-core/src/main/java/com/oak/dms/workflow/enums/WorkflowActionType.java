package com.oak.dms.workflow.enums;

/**
 * workflow_actions.action_type (V45) - the UI timeline. The values after
 * {@code restarted} are Phase 2 and never written in Phase 1.
 */
public enum WorkflowActionType {
    submitted, claimed, released, approved, rejected,
    stage_activated, stage_approved, stage_rejected, stage_unassignable,
    workflow_approved, workflow_rejected, cancelled, version_changed, restarted,
    reassigned, delegated, escalated, reminded, commented,
    info_requested, info_provided, info_declined, info_cancelled
}
