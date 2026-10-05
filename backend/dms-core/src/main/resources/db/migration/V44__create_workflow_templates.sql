-- 3.1 Workflow Automation, Phase 1 - workflow configuration.
--
--   workflow_templates           one VERSION of an approval chain for a document type
--   workflow_stages              the ordered stages of one template version
--   workflow_stage_participants  the approver entries of one stage (user | role | department)
--
-- See docs/workflow-phase1-plan.md. Deviations from the ERD, each needed:
--   * code is unique per (tenant, code, version_number), not per tenant:
--     every template version is its own row, so an in-flight instance keeps
--     pointing at immutable stages while a new version is drafted (M3).
--   * on_document_change - ADDED: what a new document version does to an
--     in-flight instance, flag_only | restart (decision 10, M5).
--   * stage_order uniqueness ignores soft-deleted stages and is checked at
--     commit, so stages can be reordered/compacted in one transaction - the
--     V31 display_order_uniq_key pattern (M4).
--   * participants carry the full audit block (M8).
-- Phase 2 adds columns only (e.g. workflow_stages.allow_info_requests).

-- =====================================================================
-- workflow_templates
-- =====================================================================
CREATE TABLE workflow_templates (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    code VARCHAR(50) NOT NULL,
    description VARCHAR(1000),
    version_number INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    on_document_change VARCHAR(20) NOT NULL DEFAULT 'flag_only',
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_workflow_templates_tenant_template UNIQUE (tenant_id, template_id),
    CONSTRAINT fk_workflow_templates_document_type FOREIGN KEY (tenant_id, document_type_id)
        REFERENCES document_types (tenant_id, document_type_id),
    CONSTRAINT ck_workflow_templates_status CHECK (status IN ('draft', 'active', 'inactive')),
    CONSTRAINT ck_workflow_templates_on_document_change CHECK (on_document_change IN ('flag_only', 'restart')),
    CONSTRAINT ck_workflow_templates_version CHECK (version_number >= 1)
);

-- Decision 7: one active template per document type per tenant.
CREATE UNIQUE INDEX uq_workflow_templates_one_active_per_type
    ON workflow_templates (tenant_id, document_type_id) WHERE status = 'active' AND is_deleted = false;
-- Versions of one template share its code.
CREATE UNIQUE INDEX uq_workflow_templates_tenant_code_version
    ON workflow_templates (tenant_id, code, version_number) WHERE is_deleted = false;
-- At most one open draft per template family.
CREATE UNIQUE INDEX uq_workflow_templates_one_draft_per_code
    ON workflow_templates (tenant_id, code) WHERE status = 'draft' AND is_deleted = false;
CREATE INDEX ix_workflow_templates_tenant_id ON workflow_templates (tenant_id);
CREATE INDEX ix_workflow_templates_document_type ON workflow_templates (tenant_id, document_type_id);

-- =====================================================================
-- workflow_stages
-- =====================================================================
CREATE TABLE workflow_stages (
    stage_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    template_id UUID NOT NULL,
    stage_order INT NOT NULL,
    name VARCHAR(150) NOT NULL,
    completion_rule VARCHAR(20) NOT NULL,
    rejection_rule VARCHAR(20) NOT NULL,
    sla_hours INT,
    -- Stored in Phase 1, acted on by the Phase 2 reminder/escalation scheduler.
    reminder_before_hours INT,
    escalation_target VARCHAR(20) NOT NULL DEFAULT 'none',
    escalation_user_id UUID REFERENCES users (user_id),
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    stage_order_uniq_key INT GENERATED ALWAYS AS (CASE WHEN is_deleted THEN NULL ELSE stage_order END) STORED,
    CONSTRAINT uq_workflow_stages_tenant_stage UNIQUE (tenant_id, stage_id),
    CONSTRAINT fk_workflow_stages_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES workflow_templates (tenant_id, template_id),
    CONSTRAINT uq_workflow_stages_template_order UNIQUE (template_id, stage_order_uniq_key) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_workflow_stages_order CHECK (stage_order >= 1),
    CONSTRAINT ck_workflow_stages_completion_rule CHECK (completion_rule IN ('all', 'any', 'majority')),
    CONSTRAINT ck_workflow_stages_rejection_rule CHECK (rejection_rule IN ('halt_immediately', 'wait_for_all')),
    CONSTRAINT ck_workflow_stages_sla CHECK (sla_hours IS NULL OR sla_hours > 0),
    CONSTRAINT ck_workflow_stages_reminder CHECK (reminder_before_hours IS NULL
        OR (sla_hours IS NOT NULL AND reminder_before_hours > 0 AND reminder_before_hours < sla_hours)),
    CONSTRAINT ck_workflow_stages_escalation_target CHECK (escalation_target IN ('none', 'department_head', 'named_user')),
    CONSTRAINT ck_workflow_stages_escalation_user CHECK ((escalation_target = 'named_user') = (escalation_user_id IS NOT NULL))
);

CREATE INDEX ix_workflow_stages_tenant_id ON workflow_stages (tenant_id);
CREATE INDEX ix_workflow_stages_template ON workflow_stages (template_id) WHERE is_deleted = false;

-- =====================================================================
-- workflow_stage_participants
-- =====================================================================
CREATE TABLE workflow_stage_participants (
    participant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    stage_id UUID NOT NULL,
    assignee_type VARCHAR(20) NOT NULL,
    -- Polymorphic soft reference (users | roles | departments by
    -- assignee_type) - no FK, like user_roles.scope_id. Validated against the
    -- tenant on write; resolved to people only when the stage activates.
    assignee_id UUID NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_workflow_stage_participants_tenant_participant UNIQUE (tenant_id, participant_id),
    CONSTRAINT fk_workflow_stage_participants_stage FOREIGN KEY (tenant_id, stage_id)
        REFERENCES workflow_stages (tenant_id, stage_id),
    CONSTRAINT ck_workflow_stage_participants_assignee_type CHECK (assignee_type IN ('user', 'role', 'department'))
);

CREATE UNIQUE INDEX uq_workflow_stage_participants_stage_assignee
    ON workflow_stage_participants (stage_id, assignee_type, assignee_id) WHERE is_deleted = false;
CREATE INDEX ix_workflow_stage_participants_tenant_id ON workflow_stage_participants (tenant_id);
-- "Is this role/department/user still used by a template?" checks.
CREATE INDEX ix_workflow_stage_participants_assignee
    ON workflow_stage_participants (tenant_id, assignee_type, assignee_id) WHERE is_deleted = false;
