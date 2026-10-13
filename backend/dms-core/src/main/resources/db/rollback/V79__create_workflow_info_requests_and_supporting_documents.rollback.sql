-- Manual rollback ("down") for V79__create_workflow_info_requests_and_supporting_documents.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Restoring the V76
-- action-type CHECK fails (on purpose) while any action still uses a value
-- V79 added; delete or keep those rows deliberately first.
ALTER TABLE workflow_actions DROP CONSTRAINT IF EXISTS ck_workflow_actions_action_type;
ALTER TABLE workflow_actions ADD CONSTRAINT ck_workflow_actions_action_type CHECK (action_type IN (
    'submitted', 'claimed', 'released', 'approved', 'rejected',
    'stage_activated', 'stage_approved', 'stage_rejected', 'stage_unassignable',
    'workflow_approved', 'workflow_rejected', 'cancelled', 'version_changed', 'restarted',
    'reassigned', 'delegated', 'escalated', 'reminded', 'commented',
    'info_requested', 'info_provided', 'info_declined', 'info_cancelled'));
DROP INDEX IF EXISTS ix_workflow_actions_info_request;
ALTER TABLE workflow_actions DROP CONSTRAINT IF EXISTS fk_workflow_actions_info_request;
ALTER TABLE workflow_actions DROP COLUMN IF EXISTS info_request_id;
DROP TABLE IF EXISTS workflow_supporting_documents;
DROP TABLE IF EXISTS workflow_info_requests;
