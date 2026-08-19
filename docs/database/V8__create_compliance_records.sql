-- Oak DMS Compliance & Records (Section 9)

-- =====================================================================
-- retention_policies (9.1)
-- =====================================================================
CREATE TABLE retention_policies (
    policy_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id UUID NOT NULL REFERENCES document_types (document_type_id),
    active_retention_days INT NOT NULL,
    archive_retention_days INT NOT NULL,
    disposal_requires_approval BOOLEAN NOT NULL DEFAULT true,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_retention_policies_tenant_document_type UNIQUE (tenant_id, document_type_id)
);

CREATE INDEX ix_retention_policies_tenant_id ON retention_policies (tenant_id);
CREATE INDEX ix_retention_policies_document_type_id ON retention_policies (document_type_id);

-- =====================================================================
-- legal_holds (9.2)
-- =====================================================================
CREATE TABLE legal_holds (
    legal_hold_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    reason TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    placed_by UUID NOT NULL REFERENCES users (user_id),
    placed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by UUID REFERENCES users (user_id),
    released_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_legal_holds_status CHECK (status IN ('active', 'released'))
);

CREATE INDEX ix_legal_holds_tenant_id ON legal_holds (tenant_id);
CREATE INDEX ix_legal_holds_document_id ON legal_holds (document_id);
CREATE INDEX ix_legal_holds_placed_by ON legal_holds (placed_by);
