-- Lets an approver retract their own decision and re-decide, but only while
-- their stage instance is still active (decision 1 of this feature - see
-- WorkflowTaskServiceImpl#retract): the instant the stage closes (its
-- completion rule satisfied, by someone else's decision), retraction is
-- permanently unavailable. The original decision is never overwritten in
-- place without a trace - these columns record that a retraction happened;
-- the full before/after is also in the audit log (WORKFLOW_TASK_RETRACTED).
--
--   workflow_tasks.retracted_at/retracted_by/retraction_reason  set when this
--       task's own decision is retracted; the task itself goes back to an
--       actionable status (pending/claimed) so the same person can re-decide.
--
-- A soft, TTL-based lock (decision 2) on the stage instance - while one
-- approver is reviewing/about to retract their decision, every other entry
-- in that same stage is blocked from acting, mirroring the existing
-- documents.signature_locked_at soft-lock pattern (V113) rather than trying
-- to hold a real row lock open across multiple HTTP requests. Expires on its
-- own (review_lock_expires_at) so an abandoned browser tab can never leave a
-- stage stuck locked forever.
ALTER TABLE workflow_tasks
    ADD COLUMN retracted_at TIMESTAMPTZ,
    ADD COLUMN retracted_by UUID,
    ADD COLUMN retraction_reason VARCHAR(2000),
    ADD CONSTRAINT ck_workflow_tasks_retraction CHECK (
        (retracted_at IS NULL) = (retracted_by IS NULL)
        AND (retracted_at IS NULL) = (retraction_reason IS NULL));

ALTER TABLE workflow_stage_instances
    ADD COLUMN review_locked_by UUID,
    ADD COLUMN review_locked_at TIMESTAMPTZ,
    ADD COLUMN review_lock_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_workflow_stage_instances_review_lock CHECK (
        (review_locked_by IS NULL) = (review_locked_at IS NULL)
        AND (review_locked_by IS NULL) = (review_lock_expires_at IS NULL));

-- ck_workflow_actions_action_type widened with 'retracted' - every prior value kept (same pattern V96 used).
ALTER TABLE workflow_actions DROP CONSTRAINT ck_workflow_actions_action_type;
ALTER TABLE workflow_actions ADD CONSTRAINT ck_workflow_actions_action_type CHECK (action_type IN (
    'submitted', 'claimed', 'released', 'approved', 'rejected', 'retracted',
    'stage_activated', 'stage_approved', 'stage_rejected', 'stage_unassignable',
    'workflow_approved', 'workflow_rejected', 'cancelled', 'version_changed', 'restarted',
    'reassigned', 'delegated', 'escalated', 'reminded', 'commented',
    'info_requested', 'info_provided', 'info_declined', 'info_cancelled',
    'info_reassigned', 'supporting_document_added', 'supporting_document_removed'));

-- =====================================================================
-- Permission: wa.approve-reject-workflow-task.retract - a new child
-- permission under the existing "Approve/Reject Workflow Task" action
-- (module WA, seeded by V6/V7/V10), same pattern V113/V119 used to add a
-- leaf permission under an existing action.
-- =====================================================================
DO
$$
DECLARE
    n bigint;
BEGIN
    WITH inserted AS (
        INSERT INTO permissions (permission_code, name, action_id)
        SELECT v.permission_code, v.name, pa.action_id
        FROM (VALUES
            ('wa.approve-reject-workflow-task.retract', 'Retract my own workflow task decision before the next approval')
        ) AS v(permission_code, name)
        JOIN permission_actions pa ON pa.action_name = 'Approve/Reject Workflow Task'
        JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'WA'
        ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING
        RETURNING 1)
    SELECT count(*) INTO n FROM inserted;
    RAISE NOTICE 'V120: % workflow-task permission(s) seeded (granted to no role)', n;
END;
$$;
