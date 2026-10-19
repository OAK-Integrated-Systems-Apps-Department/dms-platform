-- 3.2 Notification - reminder/escalation configuration columns on the
-- workflow template, stage and task tables. Add-only: no existing column
-- or row changes.
--
-- See docs/notification-plan.md (N2, N11). Points worth knowing:
--   * workflow_stages already has reminder_before_hours / escalation_target
--     / escalation_user_id (V92) for the DUE-DATE rule. These new columns are
--     for the INACTIVITY rule (N-FR-003/004), distinctly named so nothing is
--     duplicated.
--   * Template columns are the defaults; stage columns override (NULL =
--     inherit the template default). Changes apply without restart - the SLA
--     scan reads them each run (N-NFR-004).
--   * workflow_tasks.last_activity_at tracks claim/delegate/reassign/
--     info-answered; inactivity_reminder_sent_at is the fire-once stamp for
--     the inactivity reminder (escalation reuses the existing escalated_at).

ALTER TABLE workflow_templates
    ADD COLUMN default_reminder_before_hours        INT,
    ADD COLUMN default_inactivity_reminder_hours     INT,
    ADD COLUMN default_escalate_after_reminder_hours INT,
    ADD COLUMN default_escalation_target             VARCHAR(20) NOT NULL DEFAULT 'none',
    ADD COLUMN default_escalation_user_id            UUID REFERENCES users (user_id);

ALTER TABLE workflow_templates
    ADD CONSTRAINT ck_workflow_templates_default_reminder_before_hours
        CHECK (default_reminder_before_hours IS NULL OR default_reminder_before_hours > 0),
    ADD CONSTRAINT ck_workflow_templates_default_inactivity_reminder_hours
        CHECK (default_inactivity_reminder_hours IS NULL OR default_inactivity_reminder_hours > 0),
    ADD CONSTRAINT ck_workflow_templates_default_escalate_after_reminder_hours
        CHECK (default_escalate_after_reminder_hours IS NULL OR default_escalate_after_reminder_hours > 0),
    ADD CONSTRAINT ck_workflow_templates_default_escalation_target
        CHECK (default_escalation_target IN ('none', 'department_head', 'named_user')),
    ADD CONSTRAINT ck_workflow_templates_default_escalation_user
        CHECK ((default_escalation_target = 'named_user') = (default_escalation_user_id IS NOT NULL));

ALTER TABLE workflow_stages
    ADD COLUMN inactivity_reminder_hours     INT,
    ADD COLUMN escalate_after_reminder_hours INT;

ALTER TABLE workflow_stages
    ADD CONSTRAINT ck_workflow_stages_inactivity_reminder_hours
        CHECK (inactivity_reminder_hours IS NULL OR inactivity_reminder_hours > 0),
    ADD CONSTRAINT ck_workflow_stages_escalate_after_reminder_hours
        CHECK (escalate_after_reminder_hours IS NULL OR escalate_after_reminder_hours > 0);

ALTER TABLE workflow_tasks
    ADD COLUMN last_activity_at            TIMESTAMPTZ,
    ADD COLUMN inactivity_reminder_sent_at TIMESTAMPTZ;

-- Existing open rows: seed the inactivity clock from the best available stamp.
UPDATE workflow_tasks
   SET last_activity_at = COALESCE(claimed_at, acted_at, created_at)
 WHERE last_activity_at IS NULL;

-- Inactivity scan: open, undecided tasks with an activity stamp.
CREATE INDEX ix_workflow_tasks_inactivity ON workflow_tasks (last_activity_at)
    WHERE status IN ('pending', 'claimed') AND is_deleted = false AND last_activity_at IS NOT NULL;
