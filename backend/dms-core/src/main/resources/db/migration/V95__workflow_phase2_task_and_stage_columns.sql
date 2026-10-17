-- 3.1 Workflow Automation, Phase 2 - columns on the Phase 1 stage and task
-- tables. Add-only: no existing column or row changes.
--
-- See docs/workflow-phase2-plan.md (§2, §3, §7). Points worth knowing:
--   * awaiting_info is an OPEN, undecided task status (decision 20). The two
--     partial indexes whose predicate hard-codes ('pending','claimed') are
--     dropped and re-created with it - a predicate can't be altered.
--   * escalated_to snapshots the resolved escalation route (decision 6);
--     escalated_at alone (escalated_to NULL) records an escalation whose
--     target could not be used (A2).
--   * sla_paused_at/_seconds: the task's SLA clock pauses while information
--     is requested and due_at moves forward on resume (decision 14).
--   * delegated_from may be NULL with delegated_to set: an admin reassigned
--     an unclaimed pool task, so there was no previous holder (N8).

ALTER TABLE workflow_stages ADD COLUMN allow_info_requests BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE workflow_tasks
    ADD COLUMN sla_paused_at      TIMESTAMPTZ,
    ADD COLUMN sla_paused_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN escalated_to       UUID;

ALTER TABLE workflow_tasks
    ADD CONSTRAINT fk_workflow_tasks_escalated_to FOREIGN KEY (escalated_to) REFERENCES users (user_id),
    ADD CONSTRAINT ck_workflow_tasks_sla_paused_seconds CHECK (sla_paused_seconds >= 0),
    ADD CONSTRAINT ck_workflow_tasks_sla_paused_at CHECK (sla_paused_at IS NULL OR status = 'awaiting_info'),
    -- decision 12: raising a request on a pool task claims it for the requester
    ADD CONSTRAINT ck_workflow_tasks_awaiting_info_holder
        CHECK (status <> 'awaiting_info' OR assignee_type = 'user' OR claimed_by IS NOT NULL),
    ADD CONSTRAINT ck_workflow_tasks_delegation
        CHECK ((delegated_to IS NULL) = (delegated_at IS NULL)
               AND (delegated_from IS NULL OR delegated_to IS NOT NULL)
               AND (delegated_from IS NULL OR delegated_from <> delegated_to)),
    ADD CONSTRAINT ck_workflow_tasks_escalation CHECK (escalated_to IS NULL OR escalated_at IS NOT NULL);

DROP INDEX ix_workflow_tasks_open_assignee;
CREATE INDEX ix_workflow_tasks_open_assignee ON workflow_tasks (tenant_id, assignee_type, assignee_id)
    WHERE status IN ('pending', 'claimed', 'awaiting_info');

DROP INDEX ix_workflow_tasks_claimed_by;
CREATE INDEX ix_workflow_tasks_claimed_by ON workflow_tasks (tenant_id, claimed_by)
    WHERE status IN ('claimed', 'awaiting_info');

CREATE INDEX ix_workflow_tasks_delegated_to ON workflow_tasks (tenant_id, delegated_to)
    WHERE delegated_to IS NOT NULL AND status IN ('pending', 'claimed', 'awaiting_info');

CREATE INDEX ix_workflow_tasks_escalated_to ON workflow_tasks (tenant_id, escalated_to)
    WHERE escalated_to IS NOT NULL AND status IN ('pending', 'claimed', 'awaiting_info');

-- SLA scan (WorkflowSlaScheduler). awaiting_info is excluded on purpose (decision 8).
CREATE INDEX ix_workflow_tasks_sla_due ON workflow_tasks (due_at)
    WHERE status IN ('pending', 'claimed') AND due_at IS NOT NULL
      AND (reminder_sent_at IS NULL OR escalated_at IS NULL);
