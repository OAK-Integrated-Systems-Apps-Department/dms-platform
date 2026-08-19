-- Oak DMS Identity & Access schema
-- Matches the confirmed ER diagram exactly: tenants, users, roles, role_permissions, user_roles

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =====================================================================
-- tenants
-- =====================================================================
CREATE TABLE tenants (
    tenant_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_name         VARCHAR(255) NOT NULL,
    domain              VARCHAR(255) NOT NULL,
    subscription_tier   VARCHAR(50)  NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'active',
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID,
    updated_at          TIMESTAMPTZ,
    is_deleted          BOOLEAN NOT NULL DEFAULT false,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_tenants_domain UNIQUE (domain),
    CONSTRAINT ck_tenants_status CHECK (status IN ('active', 'suspended', 'archived'))
);

-- =====================================================================
-- users
-- =====================================================================
CREATE TABLE users (
    user_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    keycloak_id         UUID NOT NULL,
    email               VARCHAR(320) NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    mfa_enabled         BOOLEAN NOT NULL DEFAULT false,
    status              VARCHAR(30)  NOT NULL DEFAULT 'active',
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID,
    updated_at          TIMESTAMPTZ,
    is_deleted          BOOLEAN NOT NULL DEFAULT false,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_users_keycloak_id UNIQUE (keycloak_id),
    CONSTRAINT uq_users_tenant_email UNIQUE (tenant_id, email),
    CONSTRAINT ck_users_status CHECK (status IN ('active', 'locked', 'disabled'))
);

CREATE INDEX ix_users_tenant_id ON users (tenant_id);

-- Deferred: users.created_by / users.updated_by reference users.user_id,
-- added after the table exists to avoid a bootstrap ordering problem.
ALTER TABLE users
    ADD CONSTRAINT fk_users_created_by FOREIGN KEY (created_by) REFERENCES users (user_id),
    ADD CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by) REFERENCES users (user_id);

ALTER TABLE tenants
    ADD CONSTRAINT fk_tenants_created_by FOREIGN KEY (created_by) REFERENCES users (user_id),
    ADD CONSTRAINT fk_tenants_updated_by FOREIGN KEY (updated_by) REFERENCES users (user_id);

-- =====================================================================
-- roles
-- =====================================================================
CREATE TABLE roles (
    role_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    role_name           VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    created_by          UUID REFERENCES users (user_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES users (user_id),
    updated_at          TIMESTAMPTZ,
    is_deleted          BOOLEAN NOT NULL DEFAULT false,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_roles_tenant_name UNIQUE (tenant_id, role_name)
);

CREATE INDEX ix_roles_tenant_id ON roles (tenant_id);

-- =====================================================================
-- role_permissions  (partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE role_permissions (
    role_permission_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants (tenant_id),
    role_id              UUID NOT NULL REFERENCES roles (role_id),
    permission_code       VARCHAR(100) NOT NULL,
    resource_scope         VARCHAR(100),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_permissions UNIQUE (role_id, permission_code, resource_scope)
);

CREATE INDEX ix_role_permissions_role_id ON role_permissions (role_id);
CREATE INDEX ix_role_permissions_tenant_id ON role_permissions (tenant_id);

-- =====================================================================
-- user_roles  (partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE user_roles (
    user_role_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    user_id             UUID NOT NULL REFERENCES users (user_id),
    role_id             UUID NOT NULL REFERENCES roles (role_id),
    scope_type          VARCHAR(30) NOT NULL DEFAULT 'tenant',
    scope_id            UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_roles_scope_type CHECK (scope_type IN ('tenant', 'department', 'folder', 'document')),
    CONSTRAINT uq_user_roles UNIQUE (user_id, role_id, scope_type, scope_id)
);

CREATE INDEX ix_user_roles_user_id ON user_roles (user_id);
CREATE INDEX ix_user_roles_role_id ON user_roles (role_id);
CREATE INDEX ix_user_roles_tenant_id ON user_roles (tenant_id);
