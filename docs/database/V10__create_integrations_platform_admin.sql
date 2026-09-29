-- Oak DMS Integrations & Platform Administration (Section 11)

-- =====================================================================
-- third_party_connectors (11.1)
-- =====================================================================
CREATE TABLE third_party_connectors (
    connector_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    connector_type VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    api_key_ref VARCHAR(500) NOT NULL,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_third_party_connectors_status CHECK (status IN ('active', 'disabled', 'revoked'))
);

CREATE INDEX ix_third_party_connectors_tenant_id ON third_party_connectors (tenant_id);

-- =====================================================================
-- webhook_subscriptions (11.2)
-- =====================================================================
CREATE TABLE webhook_subscriptions (
    webhook_subscription_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    connector_id UUID REFERENCES third_party_connectors (connector_id),
    target_url VARCHAR(1000) NOT NULL,
    event_types JSONB NOT NULL,
    secret_ref VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_webhook_subscriptions_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX ix_webhook_subscriptions_tenant_id ON webhook_subscriptions (tenant_id);
CREATE INDEX ix_webhook_subscriptions_connector_id ON webhook_subscriptions (connector_id);

-- =====================================================================
-- webhook_deliveries (11.3, partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE webhook_deliveries (
    webhook_delivery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    webhook_subscription_id UUID NOT NULL REFERENCES webhook_subscriptions (webhook_subscription_id),
    event_type VARCHAR(150) NOT NULL,
    payload_ref VARCHAR(1000) NOT NULL,
    response_status INT,
    attempt_count INT NOT NULL DEFAULT 0,
    failure_reason TEXT,
    delivered_at TIMESTAMPTZ,
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_webhook_deliveries_tenant_id ON webhook_deliveries (tenant_id);
CREATE INDEX ix_webhook_deliveries_webhook_subscription_id ON webhook_deliveries (webhook_subscription_id);
CREATE INDEX ix_webhook_deliveries_correlation_id ON webhook_deliveries (correlation_id);

-- =====================================================================
-- migration_batches (11.4)
-- =====================================================================
CREATE TABLE migration_batches (
    migration_batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    source_system VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    total_records INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_migration_batches_status CHECK (status IN
        ('pending', 'running', 'completed', 'completed_with_errors', 'failed'))
);

CREATE INDEX ix_migration_batches_tenant_id ON migration_batches (tenant_id);

-- =====================================================================
-- migration_items (11.5, partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE migration_items (
    migration_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    migration_batch_id UUID NOT NULL REFERENCES migration_batches (migration_batch_id),
    source_reference VARCHAR(500) NOT NULL,
    document_id UUID REFERENCES documents (document_id),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    validation_result TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_migration_items_status CHECK (status IN ('pending', 'success', 'failed'))
);

CREATE INDEX ix_migration_items_tenant_id ON migration_items (tenant_id);
CREATE INDEX ix_migration_items_migration_batch_id ON migration_items (migration_batch_id);
CREATE INDEX ix_migration_items_document_id ON migration_items (document_id);

-- =====================================================================
-- export_jobs (11.6)
-- =====================================================================
CREATE TABLE export_jobs (
    export_job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    requested_by UUID NOT NULL REFERENCES users (user_id),
    export_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    file_object_key VARCHAR(1000),
    expires_at TIMESTAMPTZ,
    correlation_id UUID,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_export_jobs_status CHECK (status IN
        ('pending', 'processing', 'completed', 'failed', 'expired'))
);

CREATE INDEX ix_export_jobs_tenant_id ON export_jobs (tenant_id);
CREATE INDEX ix_export_jobs_requested_by ON export_jobs (requested_by);
CREATE INDEX ix_export_jobs_correlation_id ON export_jobs (correlation_id);

-- =====================================================================
-- report_definitions (11.7)
-- =====================================================================
CREATE TABLE report_definitions (
    report_definition_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    report_name VARCHAR(255) NOT NULL,
    filters_json JSONB NOT NULL,
    columns_json JSONB NOT NULL,
    grouping_json JSONB,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX ix_report_definitions_tenant_id ON report_definitions (tenant_id);

-- =====================================================================
-- saved_searches (11.8)
-- =====================================================================
CREATE TABLE saved_searches (
    saved_search_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    user_id UUID NOT NULL REFERENCES users (user_id),
    search_name VARCHAR(255) NOT NULL,
    criteria_json JSONB NOT NULL,
    sort_order VARCHAR(100),
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX ix_saved_searches_tenant_id ON saved_searches (tenant_id);
CREATE INDEX ix_saved_searches_user_id ON saved_searches (user_id);

-- =====================================================================
-- bookmarks (11.9)
-- Cross-cutting: resource_type/resource_id are polymorphic, no FK.
-- =====================================================================
CREATE TABLE bookmarks (
    bookmark_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    user_id UUID NOT NULL REFERENCES users (user_id),
    resource_type VARCHAR(20) NOT NULL,
    resource_id UUID NOT NULL,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_bookmarks_resource_type CHECK (resource_type IN ('document', 'folder'))
);

CREATE INDEX ix_bookmarks_tenant_id ON bookmarks (tenant_id);
CREATE INDEX ix_bookmarks_user_id ON bookmarks (user_id);
CREATE INDEX ix_bookmarks_resource ON bookmarks (resource_type, resource_id);

-- =====================================================================
-- pinned_documents (11.10)
-- scope_id is polymorphic (department id, etc.) per scope_type, no FK.
-- =====================================================================
CREATE TABLE pinned_documents (
    pin_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    scope_type VARCHAR(20) NOT NULL,
    scope_id UUID,
    document_id UUID NOT NULL REFERENCES documents (document_id),
    pinned_by UUID NOT NULL REFERENCES users (user_id),
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_pinned_documents_scope_type CHECK (scope_type IN ('user', 'department', 'tenant'))
);

CREATE INDEX ix_pinned_documents_tenant_id ON pinned_documents (tenant_id);
CREATE INDEX ix_pinned_documents_document_id ON pinned_documents (document_id);
CREATE INDEX ix_pinned_documents_pinned_by ON pinned_documents (pinned_by);
