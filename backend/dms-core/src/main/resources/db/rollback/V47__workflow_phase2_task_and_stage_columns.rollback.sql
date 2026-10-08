-- Manual rollback ("down") for V47__workflow_phase2_task_and_stage_columns.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Re-creates the
-- two V45 indexes with their original predicates. Run the V48 rollback first.
DROP INDEX IF EXISTS ix_workflow_tasks_sla_due;
DROP INDEX IF EXISTS ix_workflow_tasks_escalated_to;
DROP INDEX IF EXISTS ix_workflow_tasks_delegated_to;
DROP INDEX IF EXISTS ix_workflow_tasks_claimed_by;
CREATE INDEX ix_workflow_tasks_claimed_by ON workflow_tasks (tenant_id, claimed_by) WHERE status = 'claimed';
DROP INDEX IF EXISTS ix_workflow_tasks_open_assignee;
CREATE INDEX ix_workflow_tasks_open_assignee ON workflow_tasks (tenant_id, assignee_type, assignee_id)
    WHERE status IN ('pending', 'claimed');

ALTER TABLE workflow_tasks
    DROP CONSTRAINT IF EXISTS ck_workflow_tasks_escalation,
    DROP CONSTRAINT IF EXISTS ck_workflow_tasks_delegation,
    DROP CONSTRAINT IF EXISTS ck_workflow_tasks_awaiting_info_holder,
    DROP CONSTRAINT IF EXISTS ck_workflow_tasks_sla_paused_at,
    DROP CONSTRAINT IF EXISTS ck_workflow_tasks_sla_paused_seconds,
    DROP CONSTRAINT IF EXISTS fk_workflow_tasks_escalated_to;
ALTER TABLE workflow_tasks
    DROP COLUMN IF EXISTS escalated_to,
    DROP COLUMN IF EXISTS sla_paused_seconds,
    DROP COLUMN IF EXISTS sla_paused_at;

ALTER TABLE workflow_stages DROP COLUMN IF EXISTS allow_info_requests;
