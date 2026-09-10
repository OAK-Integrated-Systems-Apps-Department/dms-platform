-- Adds hybrid tenant-scoping to the permission catalog: a permission's
-- tenant_id is NULL for the platform's shared/default catalog (every
-- existing row, and everything seeded going forward via migrations
-- rather than the admin UI), or set to a specific tenant for a permission
-- that tenant created themselves through the Permission Catalog screens.
--
-- Deliberately NOT extending this to permission_modules/permission_actions
-- - those stay fully global. A tenant's custom permission still has to
-- live under one of the shared modules/actions; only the permission
-- itself (the actual grantable capability) can be tenant-specific. This
-- mirrors the existing design where every tenant already draws from the
-- same 34-module/~94-action taxonomy.
--
-- tenant_id is nullable specifically so every one of the ~226 already-
-- seeded permissions becomes "shared/global" automatically the moment
-- this column exists, with zero data migration needed - ADD COLUMN
-- without a DEFAULT leaves existing rows NULL.
ALTER TABLE permissions
    ADD COLUMN tenant_id UUID REFERENCES tenants (tenant_id);

CREATE INDEX ix_permissions_tenant_id ON permissions (tenant_id);

-- The old fully-global uq_permissions_code constraint no longer fits:
-- two different tenants must be able to create a custom permission with
-- the same code independently, without colliding with each other. Split
-- into two partial unique indexes instead of one constraint, since a
-- single UNIQUE(tenant_id, permission_code) wouldn't actually enforce
-- global-code uniqueness the way you'd expect - Postgres treats every
-- NULL as distinct from every other NULL for uniqueness purposes, so
-- multiple tenant_id IS NULL rows with the same code would NOT violate
-- a plain UNIQUE(tenant_id, permission_code) constraint.
ALTER TABLE permissions DROP CONSTRAINT uq_permissions_code;

-- Among the shared/global catalog (tenant_id IS NULL), a code must still
-- be unique platform-wide - matches the original constraint's behavior
-- for every existing row.
CREATE UNIQUE INDEX uq_permissions_code_global
    ON permissions (permission_code)
    WHERE tenant_id IS NULL;

-- Within one tenant's own custom permissions, a code must be unique to
-- that tenant - but the same code is free to be reused independently by
-- a different tenant, since PermissionServiceImpl#createPermission also
-- checks a new custom code doesn't collide with the shared catalog
-- (enforced at the application level, since that check spans both
-- partial indexes and can't be expressed as a single DB constraint).
CREATE UNIQUE INDEX uq_permissions_code_tenant
    ON permissions (tenant_id, permission_code)
    WHERE tenant_id IS NOT NULL;
