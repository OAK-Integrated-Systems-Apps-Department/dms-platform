-- 1.5 Sharing and External Access (SEA): share links, guest accounts and their grants, the sharing log,
-- the organisation's sharing policy, and the confidential flag that stops external sharing.
-- Design and API contract: docs/external-sharing-design.md.
--
-- The permission module and codes (sea.*) were seeded by V6/V7/V10; nothing is granted to a role here -
-- TENANT_ADMIN and PLATFORM_ADMIN hold every code implicitly (AuthorizationService).

-- ---------------------------------------------------------------------------------------------------
-- Confidential flag (SEA-FR-007). V102 removed the old classification_level, so "confidential" is its own
-- explicit, auditable switch. A folder's flag covers everything inside it.
-- ---------------------------------------------------------------------------------------------------
ALTER TABLE documents ADD COLUMN confidential BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE folders   ADD COLUMN confidential BOOLEAN NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------------------------------------
-- The organisation's sharing policy. One row per tenant, created on first save; a tenant with no row
-- gets the defaults below (external sharing OFF until an administrator turns it on - precondition 1.5.4).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE tenant_sharing_settings (
    tenant_id                UUID PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    external_sharing_enabled BOOLEAN     NOT NULL DEFAULT false,
    default_expiry_days      INT         NOT NULL DEFAULT 7   CHECK (default_expiry_days BETWEEN 1 AND 365),
    max_expiry_days          INT         NOT NULL DEFAULT 90  CHECK (max_expiry_days BETWEEN 1 AND 365),
    max_password_attempts    INT         NOT NULL DEFAULT 5   CHECK (max_password_attempts BETWEEN 3 AND 20),
    lockout_minutes          INT         NOT NULL DEFAULT 15  CHECK (lockout_minutes BETWEEN 1 AND 1440),
    guest_inactivity_days    INT         NOT NULL DEFAULT 90  CHECK (guest_inactivity_days BETWEEN 7 AND 730),
    on_reclassify            VARCHAR(10) NOT NULL DEFAULT 'flag' CHECK (on_reclassify IN ('flag', 'revoke')),
    updated_by               UUID REFERENCES users (user_id),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sharing_default_within_max CHECK (default_expiry_days <= max_expiry_days)
);

-- ---------------------------------------------------------------------------------------------------
-- Share links (SEA-FR-001..003, 005). Only the SHA-256 of the token is stored (SEA-NFR-001): the link
-- itself can be shown once, at creation. The password is a BCrypt hash.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE share_links (
    link_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    target_type       VARCHAR(10)  NOT NULL CHECK (target_type IN ('document', 'folder')),
    document_id       UUID,
    folder_id         UUID,
    token_hash        VARCHAR(64)     NOT NULL,
    access            VARCHAR(10)  NOT NULL CHECK (access IN ('view', 'download')),
    password_hash     VARCHAR(100),
    expires_at        TIMESTAMPTZ  NOT NULL,
    max_views         INT          CHECK (max_views > 0),
    view_count        INT          NOT NULL DEFAULT 0,
    recipient_email   VARCHAR(320),
    failed_attempts   INT          NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    revoked_by        UUID REFERENCES users (user_id),
    revoke_reason     VARCHAR(20),
    needs_review      BOOLEAN      NOT NULL DEFAULT false,
    override_reason   VARCHAR(500),
    created_by        UUID         NOT NULL REFERENCES users (user_id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_accessed_at  TIMESTAMPTZ,
    CONSTRAINT uq_share_links_token UNIQUE (token_hash),
    CONSTRAINT ck_share_links_target CHECK (
        (target_type = 'document' AND document_id IS NOT NULL AND folder_id IS NULL)
        OR (target_type = 'folder' AND folder_id IS NOT NULL AND document_id IS NULL)),
    CONSTRAINT fk_share_links_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) ON DELETE CASCADE,
    CONSTRAINT fk_share_links_folder FOREIGN KEY (tenant_id, folder_id)
        REFERENCES folders (tenant_id, folder_id) ON DELETE CASCADE
);

CREATE INDEX ix_share_links_document ON share_links (tenant_id, document_id) WHERE document_id IS NOT NULL;
CREATE INDEX ix_share_links_folder   ON share_links (tenant_id, folder_id)   WHERE folder_id IS NOT NULL;
CREATE INDEX ix_share_links_tenant   ON share_links (tenant_id, created_at DESC);
CREATE INDEX ix_share_links_review   ON share_links (tenant_id) WHERE needs_review AND revoked_at IS NULL;

-- ---------------------------------------------------------------------------------------------------
-- Guest accounts and what they can reach (SEA-FR-004). Tenant-scoped, with their own credentials - not
-- Keycloak users, so a guest can never sign in to the main application or hold a role.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE guest_accounts (
    guest_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    email             VARCHAR(320) NOT NULL,
    display_name      VARCHAR(200),
    password_hash     VARCHAR(100),
    status            VARCHAR(10)  NOT NULL DEFAULT 'invited' CHECK (status IN ('invited', 'active', 'disabled')),
    disabled_reason   VARCHAR(20),
    invite_token_hash VARCHAR(64),
    invite_expires_at TIMESTAMPTZ,
    failed_attempts   INT          NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    created_by        UUID REFERENCES users (user_id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_guest_accounts_email UNIQUE (tenant_id, email),
    CONSTRAINT uq_guest_accounts_invite UNIQUE (invite_token_hash)
);

CREATE INDEX ix_guest_accounts_email ON guest_accounts (email);

CREATE TABLE guest_grants (
    grant_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    guest_id     UUID        NOT NULL REFERENCES guest_accounts (guest_id) ON DELETE CASCADE,
    target_type  VARCHAR(10) NOT NULL CHECK (target_type IN ('document', 'folder')),
    document_id  UUID,
    folder_id    UUID,
    access       VARCHAR(10) NOT NULL CHECK (access IN ('view', 'download')),
    expires_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    revoked_by   UUID REFERENCES users (user_id),
    override_reason VARCHAR(500),
    created_by   UUID REFERENCES users (user_id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_guest_grants_target CHECK (
        (target_type = 'document' AND document_id IS NOT NULL AND folder_id IS NULL)
        OR (target_type = 'folder' AND folder_id IS NOT NULL AND document_id IS NULL)),
    CONSTRAINT fk_guest_grants_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_grants_folder FOREIGN KEY (tenant_id, folder_id)
        REFERENCES folders (tenant_id, folder_id) ON DELETE CASCADE
);

CREATE INDEX ix_guest_grants_guest    ON guest_grants (guest_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_guest_grants_document ON guest_grants (tenant_id, document_id) WHERE document_id IS NOT NULL;
CREATE INDEX ix_guest_grants_folder   ON guest_grants (tenant_id, folder_id)   WHERE folder_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------
-- The sharing log (SEA-FR-006, SEA-NFR-002): every creation, access, download, revocation and lock, with
-- who (user, guest email or "Anonymous"), where from, and when. link_id / guest_id are deliberately NOT
-- foreign keys - the log outlives a deleted guest or link. target_name is copied so it still reads
-- after a rename.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE share_events (
    event_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    event_type    VARCHAR(40)  NOT NULL,
    link_id       UUID,
    guest_id      UUID,
    target_type   VARCHAR(10),
    target_id     UUID,
    target_name   VARCHAR(500),
    actor_user_id UUID,
    actor_label   VARCHAR(320) NOT NULL,
    ip_address    VARCHAR(64),
    user_agent    VARCHAR(300),
    detail        VARCHAR(500)
);

CREATE INDEX ix_share_events_tenant ON share_events (tenant_id, occurred_at DESC);
CREATE INDEX ix_share_events_target ON share_events (tenant_id, target_id, occurred_at DESC);
CREATE INDEX ix_share_events_link   ON share_events (link_id, occurred_at DESC);
