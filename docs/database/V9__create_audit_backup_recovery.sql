-- Oak DMS Audit, Backup & Recovery (Section 10)

-- =====================================================================
-- audit_events (10.1)
-- Standard-fields exception: no updated_by/updated_at/is_deleted/deleted_at;
-- occurred_at replaces created_at. Append-only, never updated or deleted.
-- =====================================================================
CREATE TABLE audit_events (
    audit_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    user_id UUID REFERENCES users (user_id),
    event_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id UUID NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    correlation_id UUID,
    ip_address VARCHAR(64),
    user_agent VARCHAR(500),
    details_json JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_events_outcome CHECK (outcome IN ('success', 'failure'))
);

CREATE INDEX ix_audit_events_tenant_id ON audit_events (tenant_id);
CREATE INDEX ix_audit_events_user_id ON audit_events (user_id);
CREATE INDEX ix_audit_events_resource ON audit_events (resource_type, resource_id);
CREATE INDEX ix_audit_events_correlation_id ON audit_events (correlation_id);
CREATE INDEX ix_audit_events_occurred_at ON audit_events (occurred_at);

-- =====================================================================
-- recycle_bin_items (10.2, partial standard fields: created_at only)
-- Cross-cutting: resource_type/resource_id are polymorphic, no FK.
-- =====================================================================
CREATE TABLE recycle_bin_items (
    recycle_bin_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    resource_type VARCHAR(100) NOT NULL,
    resource_id UUID NOT NULL,
    deleted_by UUID NOT NULL REFERENCES users (user_id),
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    purge_after TIMESTAMPTZ NOT NULL,
    restored_by UUID REFERENCES users (user_id),
    restored_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_recycle_bin_items_tenant_id ON recycle_bin_items (tenant_id);
CREATE INDEX ix_recycle_bin_items_resource ON recycle_bin_items (resource_type, resource_id);
CREATE INDEX ix_recycle_bin_items_deleted_by ON recycle_bin_items (deleted_by);

-- =====================================================================
-- document_locks (10.3)
-- =====================================================================
CREATE TABLE document_locks (
    lock_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    locked_by UUID NOT NULL REFERENCES users (user_id),
    lock_type VARCHAR(20) NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_document_locks_lock_type CHECK (lock_type IN ('checkout', 'edit'))
);

CREATE INDEX ix_document_locks_tenant_id ON document_locks (tenant_id);
CREATE INDEX ix_document_locks_document_id ON document_locks (document_id);
CREATE INDEX ix_document_locks_locked_by ON document_locks (locked_by);

-- =====================================================================
-- co_editing_sessions (10.4)
-- =====================================================================
CREATE TABLE co_editing_sessions (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    external_editor_ref VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_co_editing_sessions_status CHECK (status IN ('active', 'ended'))
);

CREATE INDEX ix_co_editing_sessions_tenant_id ON co_editing_sessions (tenant_id);
CREATE INDEX ix_co_editing_sessions_document_id ON co_editing_sessions (document_id);
