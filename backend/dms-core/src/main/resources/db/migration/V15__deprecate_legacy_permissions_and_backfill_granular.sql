-- Every @PreAuthorize check in UserController/UserRoleController/
-- RoleController/RolePermissionController now checks a granular
-- otua.manage-users.* / asm.manage-roles-permissions.* code (seeded by
-- V10) instead of the 4 original coarse codes (user.view/user.manage/
-- role.view/role.manage, seeded by V4). Those 4 codes are no longer
-- checked anywhere in the codebase, but they're kept - not deleted - so
-- any role_permissions row still pointing at one keeps a valid FK and
-- keeps showing up wherever a role's grants are listed.
--
-- Two things this migration does:
--   1. Flags the 4 legacy codes is_deprecated, so the frontend can hide
--      them from pickers used to grant NEW permissions (see
--      use-permission-catalog.ts) without losing the ability to display
--      them wherever a role already holds one.
--   2. Backfills: any role currently granted one of the 4 legacy codes
--      also gets the full matching set of granular codes, so switching
--      the @PreAuthorize checks above to the granular codes doesn't take
--      access away from a single existing role. Grants are additive only
--      - the legacy role_permissions rows are left exactly as they were.

ALTER TABLE permissions ADD COLUMN is_deprecated BOOLEAN NOT NULL DEFAULT false;

UPDATE permissions
SET is_deprecated = true
WHERE permission_code IN ('user.view', 'user.manage', 'role.view', 'role.manage')
  AND tenant_id IS NULL;

-- =====================================================================
-- Backfill role_permissions: for every role_permissions row granting one
-- of the 4 legacy codes, add a row (same role/tenant/resource_scope) for
-- each granular code that legacy code used to stand in for.
--
--   user.view   -> otua.manage-users.view (the one granular code that
--                  covers what user.view used to gate)
--   user.manage -> every otua.manage-users.* code (user.manage gated
--                  every user-management endpoint except view-only ones,
--                  which it also satisfied via the old "view or manage"
--                  checks)
--   role.view   -> asm.manage-roles-permissions.view-role
--   role.manage -> every asm.manage-roles-permissions.* code
--
-- `is_deprecated` and this VALUES list are deliberately not derived from
-- each other - unlike V10 (which mechanically expands the real
-- module/action hierarchy), this is a one-time, hand-picked mapping from
-- 4 specific retired codes to their specific replacements.
-- =====================================================================
WITH legacy_to_granular (legacy_code, granular_code) AS (
    VALUES
        ('user.view',   'otua.manage-users.view'),
        ('user.manage', 'otua.manage-users.create'),
        ('user.manage', 'otua.manage-users.update'),
        ('user.manage', 'otua.manage-users.view'),
        ('user.manage', 'otua.manage-users.reset-password'),
        ('user.manage', 'otua.manage-users.activate-deactivate'),
        ('user.manage', 'otua.manage-users.delete'),
        ('user.manage', 'otua.manage-users.assign-role'),
        ('user.manage', 'otua.manage-users.bulk-import'),
        ('role.view',   'asm.manage-roles-permissions.view-role'),
        ('role.manage', 'asm.manage-roles-permissions.create-role'),
        ('role.manage', 'asm.manage-roles-permissions.update-role'),
        ('role.manage', 'asm.manage-roles-permissions.delete-role'),
        ('role.manage', 'asm.manage-roles-permissions.view-role'),
        ('role.manage', 'asm.manage-roles-permissions.assign-permissions'),
        ('role.manage', 'asm.manage-roles-permissions.assign-role-to-user')
),
-- DISTINCT collapses the case where a role already holds *both* e.g.
-- user.view and user.manage (which share otua.manage-users.view as a
-- target) down to one desired grant instead of trying to insert it twice.
desired_grants AS (
    SELECT DISTINCT
        rp.tenant_id,
        rp.role_id,
        gp.permission_id,
        rp.resource_scope
    FROM role_permissions rp
    JOIN permissions legacy ON legacy.permission_id = rp.permission_id
    JOIN legacy_to_granular ltg ON ltg.legacy_code = legacy.permission_code
    JOIN permissions gp ON gp.permission_code = ltg.granular_code AND gp.tenant_id IS NULL
)
INSERT INTO role_permissions (tenant_id, role_id, permission_id, resource_scope)
SELECT dg.tenant_id, dg.role_id, dg.permission_id, dg.resource_scope
FROM desired_grants dg
-- NOT EXISTS, not ON CONFLICT: uq_role_permissions is UNIQUE(role_id,
-- permission_id, resource_scope), and Postgres never considers two NULLs
-- equal for uniqueness purposes, so ON CONFLICT would silently fail to
-- catch a role that (unscoped, resource_scope NULL) already holds the
-- granular permission directly. IS NOT DISTINCT FROM treats NULL = NULL
-- as a match here, the way this check actually needs it to.
WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions existing
    WHERE existing.role_id = dg.role_id
      AND existing.permission_id = dg.permission_id
      AND existing.resource_scope IS NOT DISTINCT FROM dg.resource_scope
);
