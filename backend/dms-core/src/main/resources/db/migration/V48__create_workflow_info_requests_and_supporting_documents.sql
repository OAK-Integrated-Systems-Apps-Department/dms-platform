-- 3.1 Workflow Automation, Phase 2 - information requests and supporting
-- documents.
--
--   workflow_info_requests        an approver pauses their own task to ask someone for a document or an answer
--   workflow_supporting_documents evidence attached to an instance (linked or newly uploaded), pinned to a version
--
-- See docs/workflow-phase2-plan.md (§2, §3, §9). Points worth knowing:
--   * responded_at/responded_by are set only for answered/declined - a
--     cancel is not a response; it carries cancel_reason plus updated_*.
--   * Consistency of request -> task -> stage instance -> instance, and of
--     supporting version -> document, is checked in the service: the
--     composite FKs guarantee the tenant only (N5, N6).
--   * access_mode is always 'already_accessible' in this phase. A
--     workflow-scoped grant is a future, additive item (decision 18).
--   * The reason CHECKs use coalesce: length(btrim(NULL)) is NULL, which a
--     CHECK accepts, so a plain length test would let a NULL reason through.
--   * ck_workflow_actions_action_type is widened (A9) with info_reassigned
--     and supporting_document_added/_removed. Every V45 value is kept.

-- =====================================================================
-- workflow_info_requests
-- =====================================================================
CREATE TABLE workflow_info_requests (
    info_request_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants (tenant_id),
    instance_id       UUID NOT NULL,
    stage_instance_id UUID NOT NULL,
    task_id           UUID NOT NULL,
    requested_by      UUID NOT NULL REFERENCES users (user_id),
    requested_from    UUID NOT NULL REFERENCES users (user_id),
    message           VARCHAR(2000) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'open',
    response_message  VARCHAR(2000),
    decline_reason    VARCHAR(2000),
    cancel_reason     VARCHAR(2000),
    due_at            TIMESTAMPTZ,
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at      TIMESTAMPTZ,
    responded_by      UUID REFERENCES users (user_id),
    created_by        UUID REFERENCES users (user_id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        UUID REFERENCES users (user_id),
    updated_at        TIMESTAMPTZ,
    is_deleted        BOOLEAN NOT NULL DEFAULT false,
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT uq_workflow_info_requests_tenant_request UNIQUE (tenant_id, info_request_id),
    CONSTRAINT fk_workflow_info_requests_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_workflow_info_requests_stage_instance FOREIGN KEY (tenant_id, stage_instance_id)
        REFERENCES workflow_stage_instances (tenant_id, stage_instance_id),
    CONSTRAINT fk_workflow_info_requests_task FOREIGN KEY (tenant_id, task_id)
        REFERENCES workflow_tasks (tenant_id, task_id),
    CONSTRAINT ck_workflow_info_requests_status CHECK (status IN ('open', 'answered', 'declined', 'cancelled')),
    CONSTRAINT ck_workflow_info_requests_message CHECK (length(btrim(message)) > 0),
    CONSTRAINT ck_workflow_info_requests_not_self CHECK (requested_from <> requested_by),
    CONSTRAINT ck_workflow_info_requests_responded
        CHECK ((status IN ('answered', 'declined')) = (responded_at IS NOT NULL AND responded_by IS NOT NULL)),
    CONSTRAINT ck_workflow_info_requests_declined CHECK (status <> 'declined' OR coalesce(length(btrim(decline_reason)), 0) > 0),
    CONSTRAINT ck_workflow_info_requests_cancelled CHECK (status <> 'cancelled' OR coalesce(length(btrim(cancel_reason)), 0) > 0)
);

CREATE INDEX ix_workflow_info_requests_tenant_id ON workflow_info_requests (tenant_id);
CREATE INDEX ix_workflow_info_requests_task_open ON workflow_info_requests (task_id)
    WHERE status = 'open' AND is_deleted = false;
CREATE INDEX ix_workflow_info_requests_requested_from ON workflow_info_requests (tenant_id, requested_from, status)
    WHERE is_deleted = false;
CREATE INDEX ix_workflow_info_requests_instance ON workflow_info_requests (instance_id, requested_at);

-- =====================================================================
-- workflow_supporting_documents
-- =====================================================================
CREATE TABLE workflow_supporting_documents (
    supporting_document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants (tenant_id),
    instance_id            UUID NOT NULL,
    info_request_id        UUID,
    document_id            UUID NOT NULL,
    document_version_id    UUID NOT NULL,
    source                 VARCHAR(20) NOT NULL,
    added_by               UUID NOT NULL REFERENCES users (user_id),
    access_mode            VARCHAR(30) NOT NULL DEFAULT 'already_accessible',
    access_revoked_at      TIMESTAMPTZ,
    note                   VARCHAR(2000),
    added_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             UUID REFERENCES users (user_id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by             UUID REFERENCES users (user_id),
    updated_at             TIMESTAMPTZ,
    is_deleted             BOOLEAN NOT NULL DEFAULT false,
    deleted_at             TIMESTAMPTZ,
    CONSTRAINT uq_workflow_supporting_documents_tenant_sd UNIQUE (tenant_id, supporting_document_id),
    CONSTRAINT fk_workflow_supporting_documents_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_workflow_supporting_documents_info_request FOREIGN KEY (tenant_id, info_request_id)
        REFERENCES workflow_info_requests (tenant_id, info_request_id),
    CONSTRAINT fk_workflow_supporting_documents_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_workflow_supporting_documents_version FOREIGN KEY (tenant_id, document_version_id)
        REFERENCES document_versions (tenant_id, version_id),
    CONSTRAINT ck_workflow_supporting_documents_source CHECK (source IN ('linked_existing', 'uploaded_new')),
    CONSTRAINT ck_workflow_supporting_documents_access_mode
        CHECK (access_mode IN ('already_accessible', 'workflow_scoped_grant')),
    CONSTRAINT ck_workflow_supporting_documents_revoked
        CHECK (access_revoked_at IS NULL OR access_mode = 'workflow_scoped_grant')
);

CREATE UNIQUE INDEX uq_workflow_supporting_documents_instance_version
    ON workflow_supporting_documents (instance_id, document_version_id) WHERE is_deleted = false;
CREATE INDEX ix_workflow_supporting_documents_tenant_id ON workflow_supporting_documents (tenant_id);
CREATE INDEX ix_workflow_supporting_documents_instance ON workflow_supporting_documents (instance_id)
    WHERE is_deleted = false;
CREATE INDEX ix_workflow_supporting_documents_request ON workflow_supporting_documents (info_request_id)
    WHERE info_request_id IS NOT NULL;
CREATE INDEX ix_workflow_supporting_documents_document ON workflow_supporting_documents (tenant_id, document_id);

-- =====================================================================
-- workflow_actions: link to the request, widen the action types (A9)
-- =====================================================================
ALTER TABLE workflow_actions ADD COLUMN info_request_id UUID;
ALTER TABLE workflow_actions ADD CONSTRAINT fk_workflow_actions_info_request
    FOREIGN KEY (tenant_id, info_request_id) REFERENCES workflow_info_requests (tenant_id, info_request_id);
CREATE INDEX ix_workflow_actions_info_request ON workflow_actions (info_request_id) WHERE info_request_id IS NOT NULL;

ALTER TABLE workflow_actions DROP CONSTRAINT ck_workflow_actions_action_type;
ALTER TABLE workflow_actions ADD CONSTRAINT ck_workflow_actions_action_type CHECK (action_type IN (
    'submitted', 'claimed', 'released', 'approved', 'rejected',
    'stage_activated', 'stage_approved', 'stage_rejected', 'stage_unassignable',
    'workflow_approved', 'workflow_rejected', 'cancelled', 'version_changed', 'restarted',
    'reassigned', 'delegated', 'escalated', 'reminded', 'commented',
    'info_requested', 'info_provided', 'info_declined', 'info_cancelled',
    'info_reassigned', 'supporting_document_added', 'supporting_document_removed'));
