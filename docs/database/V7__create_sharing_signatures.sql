-- Oak DMS Sharing & Signatures (Section 8)

-- =====================================================================
-- shares (8.1)
-- =====================================================================
CREATE TABLE shares (
    share_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    shared_by UUID NOT NULL REFERENCES users (user_id),
    recipient_email VARCHAR(320),
    access_level VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ,
    token_hash VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    download_allowed BOOLEAN NOT NULL DEFAULT false,
    view_count_limit INT,
    view_count_used INT NOT NULL DEFAULT 0,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES users (user_id),
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_shares_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_shares_access_level CHECK (access_level IN ('view', 'comment', 'edit')),
    CONSTRAINT ck_shares_status CHECK (status IN ('active', 'expired', 'revoked', 'disabled'))
);

CREATE INDEX ix_shares_document_id ON shares (document_id);
CREATE INDEX ix_shares_tenant_id ON shares (tenant_id);
CREATE INDEX ix_shares_shared_by ON shares (shared_by);

-- =====================================================================
-- share_access_events (8.2, partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE share_access_events (
    share_access_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    share_id UUID NOT NULL REFERENCES shares (share_id),
    event_type VARCHAR(30) NOT NULL,
    ip_address VARCHAR(64),
    user_agent VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_share_access_events_event_type CHECK (event_type IN
        ('view', 'download', 'failed', 'expired', 'password_failure'))
);

CREATE INDEX ix_share_access_events_tenant_id ON share_access_events (tenant_id);
CREATE INDEX ix_share_access_events_share_id ON share_access_events (share_id);

-- =====================================================================
-- digital_signatures (8.3)
-- =====================================================================
CREATE TABLE digital_signatures (
    signature_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    signer_email VARCHAR(320) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'sent',
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_digital_signatures_status CHECK (status IN
        ('sent', 'viewed', 'signed', 'declined', 'voided'))
);

CREATE INDEX ix_digital_signatures_document_id ON digital_signatures (document_id);
CREATE INDEX ix_digital_signatures_tenant_id ON digital_signatures (tenant_id);
