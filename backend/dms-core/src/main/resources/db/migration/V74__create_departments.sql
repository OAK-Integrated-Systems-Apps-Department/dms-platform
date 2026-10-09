-- 3.1 Workflow Automation, Phase 1 - departments.
--
-- user_roles.scope_type has allowed 'department' since V1, but nothing ever
-- backed it. Workflow stage participants can now name a department as a
-- claimable approver pool, so departments get a real table and every user
-- gets an optional primary department (users.department_id). Pool
-- membership is direct members only - child departments are not included
-- (docs/workflow-phase1-plan.md, decisions table).
--
-- Composite (tenant_id, X) FKs, like V39/V42, so a user can never point at
-- another tenant's department and a department never at another tenant's
-- parent. Deeper parent cycles (A -> B -> A) are refused by DepartmentService.

CREATE TABLE departments (
    department_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    name VARCHAR(150) NOT NULL,
    code VARCHAR(50) NOT NULL,
    parent_department_id UUID,
    -- Phase 2 escalation target (workflow_stages.escalation_target = 'department_head').
    head_user_id UUID REFERENCES users (user_id),
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_departments_tenant_department UNIQUE (tenant_id, department_id),
    CONSTRAINT fk_departments_parent FOREIGN KEY (tenant_id, parent_department_id)
        REFERENCES departments (tenant_id, department_id),
    CONSTRAINT ck_departments_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_departments_not_own_parent CHECK (parent_department_id IS NULL OR parent_department_id <> department_id)
);

CREATE UNIQUE INDEX uq_departments_tenant_code ON departments (tenant_id, code) WHERE is_deleted = false;
CREATE INDEX ix_departments_tenant_id ON departments (tenant_id);
CREATE INDEX ix_departments_parent ON departments (parent_department_id) WHERE parent_department_id IS NOT NULL;

-- A user's primary department. Nullable - MATCH SIMPLE lets a NULL
-- department_id skip the composite FK check.
ALTER TABLE users ADD COLUMN department_id UUID;
ALTER TABLE users ADD CONSTRAINT fk_users_department FOREIGN KEY (tenant_id, department_id)
    REFERENCES departments (tenant_id, department_id);
CREATE INDEX ix_users_department ON users (tenant_id, department_id)
    WHERE department_id IS NOT NULL AND is_deleted = false;
