-- 3.1 Workflow Automation, Phase 1 - workflow runtime.
--
--   workflow_instances        one run of a template version against one document version
--   workflow_stage_instances  one row per template stage, created at submission
--   workflow_tasks            one task per participant entry, created when its stage activates
--   workflow_actions          append-only, queryable history for the UI timeline
--
-- See docs/workflow-phase1-plan.md (§2-§6). Points worth knowing:
--   * uq_workflow_instances_one_in_progress (decision 8) has no is_deleted
--     predicate on purpose - an in-flight instance is never soft-deleted.
--   * entry_count - ADDED: n, the number of participant entries of the stage
--     when it activated, so the completion rule never needs a count query
--     while the stage row is locked (M6).
--   * The CHECKs on workflow_tasks.status and workflow_actions.action_type
--     already list the Phase 2 values (awaiting_info, delegated, reassigned,
--     info_*, ...). Phase 1 never writes them; Phase 2 then only adds
--     tables and columns and never touches a Phase 1 constraint.
--   * delegated_*/reminder_sent_at/escalated_at are Phase 2 columns, null
--     throughout Phase 1.
--   * workflow_actions has no audit block (append-only, like audit_events)
--     and no FK to users for system actions (actor_user_id NULL).

-- =====================================================================
-- workflow_instances
-- =====================================================================
CREATE TABLE workflow_instances (
    instance_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL,
    -- Snapshot at submission: documents.latest_version_id (M2).
    document_version_id UUID NOT NULL,
    template_id UUID NOT NULL,
    template_version_number INT NOT NULL,
    current_stage_instance_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'in_progress',
    initiated_by UUID NOT NULL REFERENCES users (user_id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    outcome_reason VARCHAR(2000),
    version_changed_flag BOOLEAN NOT NULL DEFAULT false,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_workflow_instances_tenant_instance UNIQUE (tenant_id, instance_id),
    CONSTRAINT fk_workflow_instances_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_workflow_instances_document_version FOREIGN KEY (tenant_id, document_version_id)
        REFERENCES document_versions (tenant_id, version_id),
    CONSTRAINT fk_workflow_instances_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES workflow_templates (tenant_id, template_id),
    CONSTRAINT ck_workflow_instances_status CHECK (status IN ('in_progress', 'approved', 'rejected', 'cancelled')),
    CONSTRAINT ck_workflow_instances_completed CHECK ((status = 'in_progress') = (completed_at IS NULL)),
    CONSTRAINT ck_workflow_instances_outcome_reason CHECK (status NOT IN ('rejected', 'cancelled')
        OR length(btrim(outcome_reason)) > 0)
);

-- Decision 8: one in_progress instance per document.
CREATE UNIQUE INDEX uq_workflow_instances_one_in_progress
    ON workflow_instances (tenant_id, document_id) WHERE status = 'in_progress';
CREATE INDEX ix_workflow_instances_tenant_id ON workflow_instances (tenant_id);
CREATE INDEX ix_workflow_instances_document ON workflow_instances (tenant_id, document_id, started_at DESC);
CREATE INDEX ix_workflow_instances_initiator ON workflow_instances (tenant_id, initiated_by, status);

-- =====================================================================
-- workflow_stage_instances
-- =====================================================================
CREATE TABLE workflow_stage_instances (
    stage_instance_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    instance_id UUID NOT NULL,
    stage_id UUID NOT NULL,
    stage_order INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'not_started',
    entry_count INT NOT NULL DEFAULT 0,
    required_count INT NOT NULL DEFAULT 0,
    approved_count INT NOT NULL DEFAULT 0,
    rejected_count INT NOT NULL DEFAULT 0,
    activated_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_workflow_stage_instances_tenant_stage_instance UNIQUE (tenant_id, stage_instance_id),
    CONSTRAINT uq_workflow_stage_instances_instance_order UNIQUE (instance_id, stage_order),
    CONSTRAINT fk_workflow_stage_instances_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_workflow_stage_instances_stage FOREIGN KEY (tenant_id, stage_id)
        REFERENCES workflow_stages (tenant_id, stage_id),
    CONSTRAINT ck_workflow_stage_instances_status CHECK (status IN ('not_started', 'active', 'approved', 'rejected', 'skipped')),
    CONSTRAINT ck_workflow_stage_instances_counts CHECK (approved_count >= 0 AND rejected_count >= 0
        AND approved_count + rejected_count <= entry_count AND required_count <= entry_count)
);

-- Invariant: at most one active stage per instance.
CREATE UNIQUE INDEX uq_workflow_stage_instances_one_active
    ON workflow_stage_instances (instance_id) WHERE status = 'active';
CREATE INDEX ix_workflow_stage_instances_tenant_id ON workflow_stage_instances (tenant_id);
-- Phase 2 SLA/reminder scan.
CREATE INDEX ix_workflow_stage_instances_due ON workflow_stage_instances (due_at)
    WHERE status = 'active' AND due_at IS NOT NULL;

-- Circular with workflow_stage_instances.instance_id - deferred, like
-- documents <-> document_versions (V32), so an instance and its stage
-- instances can be inserted in either order within one transaction.
ALTER TABLE workflow_instances ADD CONSTRAINT fk_workflow_instances_current_stage
    FOREIGN KEY (tenant_id, current_stage_instance_id)
    REFERENCES workflow_stage_instances (tenant_id, stage_instance_id)
    DEFERRABLE INITIALLY DEFERRED;

-- =====================================================================
-- workflow_tasks
-- =====================================================================
CREATE TABLE workflow_tasks (
    task_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    stage_instance_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    assignee_type VARCHAR(20) NOT NULL,
    -- A user, or the pool (role/department) - pools are claimable.
    assignee_id UUID NOT NULL,
    claimed_by UUID REFERENCES users (user_id),
    claimed_at TIMESTAMPTZ,
    delegated_from UUID REFERENCES users (user_id),
    delegated_to UUID REFERENCES users (user_id),
    delegated_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    decision VARCHAR(10),
    decision_reason VARCHAR(2000),
    acted_by UUID REFERENCES users (user_id),
    acted_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    reminder_sent_at TIMESTAMPTZ,
    escalated_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_workflow_tasks_tenant_task UNIQUE (tenant_id, task_id),
    -- One task per participant entry per stage instance (delegation in Phase 2 is in place).
    CONSTRAINT uq_workflow_tasks_stage_participant UNIQUE (stage_instance_id, participant_id),
    CONSTRAINT fk_workflow_tasks_stage_instance FOREIGN KEY (tenant_id, stage_instance_id)
        REFERENCES workflow_stage_instances (tenant_id, stage_instance_id),
    CONSTRAINT fk_workflow_tasks_participant FOREIGN KEY (tenant_id, participant_id)
        REFERENCES workflow_stage_participants (tenant_id, participant_id),
    CONSTRAINT ck_workflow_tasks_assignee_type CHECK (assignee_type IN ('user', 'role', 'department')),
    CONSTRAINT ck_workflow_tasks_status CHECK (status IN ('pending', 'claimed', 'awaiting_info', 'approved',
        'rejected', 'delegated', 'expired', 'cancelled')),
    CONSTRAINT ck_workflow_tasks_decision CHECK (decision IS NULL OR decision IN ('approve', 'reject')),
    CONSTRAINT ck_workflow_tasks_decided CHECK ((status IN ('approved', 'rejected'))
        = (decision IS NOT NULL AND acted_by IS NOT NULL AND acted_at IS NOT NULL)),
    CONSTRAINT ck_workflow_tasks_decision_matches CHECK (decision IS NULL OR (decision = 'approve') = (status = 'approved')),
    CONSTRAINT ck_workflow_tasks_reject_reason CHECK (status <> 'rejected' OR length(btrim(decision_reason)) > 0),
    CONSTRAINT ck_workflow_tasks_claimed CHECK (status <> 'claimed' OR claimed_by IS NOT NULL)
);

-- WA-FR-013: a user decides at most once per stage instance.
CREATE UNIQUE INDEX uq_workflow_tasks_one_decision_per_user
    ON workflow_tasks (stage_instance_id, acted_by) WHERE status IN ('approved', 'rejected');
CREATE INDEX ix_workflow_tasks_tenant_id ON workflow_tasks (tenant_id);
CREATE INDEX ix_workflow_tasks_stage_instance ON workflow_tasks (stage_instance_id);
CREATE INDEX ix_workflow_tasks_open_assignee ON workflow_tasks (tenant_id, assignee_type, assignee_id)
    WHERE status IN ('pending', 'claimed');
CREATE INDEX ix_workflow_tasks_claimed_by ON workflow_tasks (tenant_id, claimed_by) WHERE status = 'claimed';

-- =====================================================================
-- workflow_actions
-- =====================================================================
CREATE TABLE workflow_actions (
    action_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    instance_id UUID NOT NULL,
    stage_instance_id UUID,
    task_id UUID,
    action_type VARCHAR(40) NOT NULL,
    -- NULL = the system (auto-advance, version-change handling).
    actor_user_id UUID REFERENCES users (user_id),
    reason VARCHAR(2000),
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_workflow_actions_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_workflow_actions_stage_instance FOREIGN KEY (tenant_id, stage_instance_id)
        REFERENCES workflow_stage_instances (tenant_id, stage_instance_id),
    CONSTRAINT fk_workflow_actions_task FOREIGN KEY (tenant_id, task_id)
        REFERENCES workflow_tasks (tenant_id, task_id),
    CONSTRAINT ck_workflow_actions_action_type CHECK (action_type IN (
        'submitted', 'claimed', 'released', 'approved', 'rejected',
        'stage_activated', 'stage_approved', 'stage_rejected', 'stage_unassignable',
        'workflow_approved', 'workflow_rejected', 'cancelled', 'version_changed', 'restarted',
        -- Phase 2 (allowed now, never written in Phase 1):
        'reassigned', 'delegated', 'escalated', 'reminded', 'commented',
        'info_requested', 'info_provided', 'info_declined', 'info_cancelled'))
);

CREATE INDEX ix_workflow_actions_tenant_id ON workflow_actions (tenant_id);
CREATE INDEX ix_workflow_actions_instance ON workflow_actions (instance_id, occurred_at);

-- Append-only, like audit_events (V23 - see its caveat: this only binds a
-- non-owning, non-superuser application role).
REVOKE UPDATE, DELETE ON workflow_actions FROM PUBLIC;
