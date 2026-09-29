-- Oak DMS Identity & Access schema (continued)
-- Adds the two Identity & Access tables NOT already created in V1:
--   3.6 user_groups
--   3.7 group_members
-- (tenants, users, roles, role_permissions, user_roles already exist from V1.)

-- =====================================================================
-- user_groups
-- =====================================================================
CREATE TABLE user_groups (
    group_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants (tenant_id),
    group_name           VARCHAR(255) NOT NULL,
    description           VARCHAR(500),
    created_by            UUID REFERENCES users (user_id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID REFERENCES users (user_id),
    updated_at            TIMESTAMPTZ,
    is_deleted             BOOLEAN NOT NULL DEFAULT false,
    deleted_at             TIMESTAMPTZ,
    CONSTRAINT uq_user_groups_tenant_name UNIQUE (tenant_id, group_name)
);

CREATE INDEX ix_user_groups_tenant_id ON user_groups (tenant_id);

-- =====================================================================
-- group_members  (partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE group_members (
    group_member_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants (tenant_id),
    group_id              UUID NOT NULL REFERENCES user_groups (group_id),
    user_id                UUID NOT NULL REFERENCES users (user_id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_members UNIQUE (group_id, user_id)
);

CREATE INDEX ix_group_members_group_id ON group_members (group_id);
CREATE INDEX ix_group_members_user_id ON group_members (user_id);
CREATE INDEX ix_group_members_tenant_id ON group_members (tenant_id);
