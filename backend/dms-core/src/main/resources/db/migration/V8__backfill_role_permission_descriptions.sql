-- Backfills descriptions on the module/action rows that an earlier
-- migration (the one introducing permission_modules/permission_actions
-- and deriving rows generically from permissions.permission_code) left
-- NULL. That migration's INSERT statements never listed a `description`
-- column, so every row it produced - ROLE, USER, and any custom module
-- carried forward from pre-existing role_permissions.permission_code
-- values (e.g. MANAGER) - was written without one.
--
-- This migration only touches rows that are still missing a description,
-- so it's safe to run regardless of whether some of these codes were
-- later backfilled by hand.

-- =====================================================================
-- Known modules: ROLE, USER (from the hardcoded permission seeds), plus
-- MANAGER (a carried-forward custom permission_code). Add further
-- WHEN branches here if other custom module_codes turn up with a NULL
-- description.
-- =====================================================================
UPDATE permission_modules
SET description = CASE module_code
    WHEN 'ROLE'    THEN 'Manages role definitions and the permission grants assigned to each role.'
    WHEN 'USER'    THEN 'Manages user accounts within a tenant.'
    WHEN 'MANAGER' THEN 'Manages manager-level administrative capabilities carried forward from legacy role_permissions grants.'
    ELSE description
END
WHERE module_code IN ('ROLE', 'USER', 'MANAGER')
  AND (description IS NULL OR description = '');

-- =====================================================================
-- Known actions: VIEW / MANAGE, scoped per module so e.g. ROLE.VIEW and
-- USER.VIEW can get module-specific wording rather than one generic
-- string for every VIEW action on the platform.
-- =====================================================================
UPDATE permission_actions pa
SET description = CASE
    WHEN pm.module_code = 'ROLE'    AND pa.action_code = 'VIEW'   THEN 'View role definitions and their permission grants.'
    WHEN pm.module_code = 'ROLE'    AND pa.action_code = 'MANAGE' THEN 'Create, update, and delete roles and manage their permission grants.'
    WHEN pm.module_code = 'USER'    AND pa.action_code = 'VIEW'   THEN 'View user profiles within the tenant.'
    WHEN pm.module_code = 'USER'    AND pa.action_code = 'MANAGE' THEN 'Create, update, and deactivate users within the tenant.'
    WHEN pm.module_code = 'MANAGER' AND pa.action_code = 'VIEW'   THEN 'View manager-level administrative settings.'
    WHEN pm.module_code = 'MANAGER' AND pa.action_code = 'MANAGE' THEN 'Modify manager-level administrative settings.'
    ELSE pa.description
END
FROM permission_modules pm
WHERE pa.module_id = pm.module_id
  AND pm.module_code IN ('ROLE', 'USER', 'MANAGER')
  AND pa.action_code IN ('VIEW', 'MANAGE')
  AND (pa.description IS NULL OR pa.description = '');

-- =====================================================================
-- Visibility check (not a hard failure): report anything still NULL so
-- it doesn't go unnoticed. Any module/action here came from a custom
-- permission_code this migration didn't anticipate - add a WHEN branch
-- above once you know what it should say, then re-run a follow-up
-- migration.
-- =====================================================================
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT module_code, NULL::text AS action_code
        FROM permission_modules
        WHERE description IS NULL OR description = ''
        UNION ALL
        SELECT pm.module_code, pa.action_code
        FROM permission_actions pa
        JOIN permission_modules pm ON pm.module_id = pa.module_id
        WHERE pa.description IS NULL OR pa.description = ''
    LOOP
        RAISE NOTICE 'Still missing description: module=%, action=%', r.module_code, r.action_code;
    END LOOP;
END $$;
