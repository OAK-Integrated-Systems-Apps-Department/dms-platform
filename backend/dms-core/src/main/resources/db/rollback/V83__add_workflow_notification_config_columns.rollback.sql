-- Manual rollback ("down") for V83__add_workflow_notification_config_columns.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Add-only migration,
-- so this simply drops the index, constraints and columns it added. Run the
-- V84 rollback first.
DROP INDEX IF EXISTS ix_workflow_tasks_inactivity;

ALTER TABLE workflow_tasks
    DROP COLUMN IF EXISTS inactivity_reminder_sent_at,
    DROP COLUMN IF EXISTS last_activity_at;

ALTER TABLE workflow_stages
    DROP CONSTRAINT IF EXISTS ck_workflow_stages_escalate_after_reminder_hours,
    DROP CONSTRAINT IF EXISTS ck_workflow_stages_inactivity_reminder_hours;
ALTER TABLE workflow_stages
    DROP COLUMN IF EXISTS escalate_after_reminder_hours,
    DROP COLUMN IF EXISTS inactivity_reminder_hours;

ALTER TABLE workflow_templates
    DROP CONSTRAINT IF EXISTS ck_workflow_templates_default_escalation_user,
    DROP CONSTRAINT IF EXISTS ck_workflow_templates_default_escalation_target,
    DROP CONSTRAINT IF EXISTS ck_workflow_templates_default_escalate_after_reminder_hours,
    DROP CONSTRAINT IF EXISTS ck_workflow_templates_default_inactivity_reminder_hours,
    DROP CONSTRAINT IF EXISTS ck_workflow_templates_default_reminder_before_hours;
ALTER TABLE workflow_templates
    DROP COLUMN IF EXISTS default_escalation_user_id,
    DROP COLUMN IF EXISTS default_escalation_target,
    DROP COLUMN IF EXISTS default_escalate_after_reminder_hours,
    DROP COLUMN IF EXISTS default_inactivity_reminder_hours,
    DROP COLUMN IF EXISTS default_reminder_before_hours;
