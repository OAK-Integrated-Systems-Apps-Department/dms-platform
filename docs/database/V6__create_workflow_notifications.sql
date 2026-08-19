-- Oak DMS Workflow & Notifications (Section 7)

-- =====================================================================
-- workflows (7.1)
-- =====================================================================
CREATE TABLE workflows (
    workflow_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    workflow_type VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    started_by UUID NOT NULL REFERENCES users (user_id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_workflows_status CHECK (status IN
        ('pending', 'in_progress', 'approved', 'rejected', 'cancelled'))
);

CREATE INDEX ix_workflows_tenant_id ON workflows (tenant_id);
CREATE INDEX ix_workflows_document_id ON workflows (document_id);
CREATE INDEX ix_workflows_started_by ON workflows (started_by);

-- =====================================================================
-- workflow_tasks (7.2)
-- =====================================================================
CREATE TABLE workflow_tasks (
    workflow_task_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    workflow_id UUID NOT NULL REFERENCES workflows (workflow_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    stage_number INT NOT NULL,
    stage_name VARCHAR(255) NOT NULL,
    assigned_user_id UUID REFERENCES users (user_id),
    assigned_role_id UUID REFERENCES roles (role_id),
    status VARCHAR(30) NOT NULL DEFAULT 'not_started',
    decision VARCHAR(30),
    decision_comment VARCHAR(1000),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    due_at TIMESTAMPTZ,
    acted_at TIMESTAMPTZ,
    delegated_from_user_id UUID REFERENCES users (user_id),
    delegated_to_user_id UUID REFERENCES users (user_id),
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_workflow_tasks_status CHECK (status IN
        ('not_started', 'pending', 'in_progress', 'approved', 'rejected', 'delegated', 'cancelled', 'expired')),
    CONSTRAINT ck_workflow_tasks_decision CHECK (decision IS NULL OR decision IN
        ('approved', 'rejected', 'changes_requested'))
);

CREATE INDEX ix_workflow_tasks_tenant_id ON workflow_tasks (tenant_id);
CREATE INDEX ix_workflow_tasks_workflow_id ON workflow_tasks (workflow_id);
CREATE INDEX ix_workflow_tasks_document_id ON workflow_tasks (document_id);
CREATE INDEX ix_workflow_tasks_assigned_user_id ON workflow_tasks (assigned_user_id);
CREATE INDEX ix_workflow_tasks_assigned_role_id ON workflow_tasks (assigned_role_id);

-- =====================================================================
-- notifications (7.3)
-- =====================================================================
CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    user_id UUID NOT NULL REFERENCES users (user_id),
    channel VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,
    read_status BOOLEAN NOT NULL DEFAULT false,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('email', 'sms', 'in-app'))
);

CREATE INDEX ix_notifications_tenant_id ON notifications (tenant_id);
CREATE INDEX ix_notifications_user_id ON notifications (user_id);
