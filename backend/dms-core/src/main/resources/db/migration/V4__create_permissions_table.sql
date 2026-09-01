-- Normalizes role_permissions.permission_code (previously a free-form
-- string, reused across roles only by coincidence of matching text) into a
-- real, platform-wide permissions catalog with a foreign key. This makes
-- Role <-> Permission an explicit many-to-many relationship: any permission
-- can be granted to any number of roles, and any role can hold any number
-- of permissions, each grant tracked as its own row in role_permissions.

-- =====================================================================
-- permissions  (platform-wide catalog, not tenant-scoped)
-- =====================================================================
CREATE TABLE permissions (
    permission_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    permission_code       VARCHAR(100) NOT NULL,
    name                  VARCHAR(150) NOT NULL,
    description           VARCHAR(500),
    category              VARCHAR(100),
    created_by            UUID REFERENCES users (user_id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID REFERENCES users (user_id),
    updated_at            TIMESTAMPTZ,
    is_deleted            BOOLEAN NOT NULL DEFAULT false,
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT uq_permissions_code UNIQUE (permission_code)
);

-- Seed the permission codes already referenced by @PreAuthorize checks in
-- the codebase (role.view/role.manage/user.view/user.manage), so existing
-- role_permissions rows have a catalog entry to point at and current
-- authorization checks keep working after the cutover below.
INSERT INTO permissions (permission_code, name, category, description) VALUES
    ('role.view',   'View Roles',   'RBAC',            'View role definitions and their permission grants'),
    ('role.manage', 'Manage Roles', 'RBAC',            'Create, update, delete roles and manage their permission grants'),
    ('user.view',   'View Users',   'User Management', 'View user profiles within the tenant'),
    ('user.manage', 'Manage Users', 'User Management', 'Create, update, deactivate users within the tenant');

-- Carry forward any permission_code already granted in role_permissions
-- that isn't in the seed list above (a custom code some tenant already
-- granted), so no existing grant is silently orphaned by the cutover.
INSERT INTO permissions (permission_code, name, category)
SELECT DISTINCT rp.permission_code, rp.permission_code, 'Uncategorized'
FROM role_permissions rp
LEFT JOIN permissions p ON p.permission_code = rp.permission_code
WHERE p.permission_id IS NULL;

-- =====================================================================
-- role_permissions: swap the free-text permission_code column for a real
-- FK to permissions.
-- =====================================================================
ALTER TABLE role_permissions ADD COLUMN permission_id UUID;

UPDATE role_permissions rp
SET permission_id = p.permission_id
FROM permissions p
WHERE p.permission_code = rp.permission_code;

ALTER TABLE role_permissions ALTER COLUMN permission_id SET NOT NULL;
ALTER TABLE role_permissions
    ADD CONSTRAINT fk_role_permissions_permission
    FOREIGN KEY (permission_id) REFERENCES permissions (permission_id);

ALTER TABLE role_permissions DROP CONSTRAINT uq_role_permissions;
ALTER TABLE role_permissions DROP COLUMN permission_code;

ALTER TABLE role_permissions
    ADD CONSTRAINT uq_role_permissions UNIQUE (role_id, permission_id, resource_scope);

CREATE INDEX ix_role_permissions_permission_id ON role_permissions (permission_id);
