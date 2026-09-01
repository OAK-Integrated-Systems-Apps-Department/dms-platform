-- Introduces a proper module/action hierarchy above the existing
-- permissions catalog, replacing the free-text `permissions.category`
-- column (e.g. 'RBAC', 'User Management') with real, reusable rows.
--
-- Hierarchy:
--   permission_modules  (1) --> (many) permission_actions
--   permission_actions  (1) --> (many) permissions
--
-- Example: module "ROLE" has actions VIEW/MANAGE; module "USER" also has
-- actions VIEW/MANAGE (same action codes, different module -> no conflict,
-- enforced by UNIQUE(module_id, action_code)). Each permission then points
-- at exactly one action, e.g. permission 'role.view' -> action ROLE.VIEW.

-- =====================================================================
-- permission_modules  (platform-wide catalog, not tenant-scoped)
-- =====================================================================
CREATE TABLE permission_modules (
    module_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_code           VARCHAR(50) NOT NULL,
    module_name           VARCHAR(150) NOT NULL,
    description           VARCHAR(500),
    created_by            UUID REFERENCES users (user_id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID REFERENCES users (user_id),
    updated_at            TIMESTAMPTZ,
    is_deleted            BOOLEAN NOT NULL DEFAULT false,
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT uq_permission_modules_code UNIQUE (module_code)
);

-- =====================================================================
-- permission_actions  (belongs to exactly one module)
-- =====================================================================
CREATE TABLE permission_actions (
    action_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id             UUID NOT NULL REFERENCES permission_modules (module_id),
    action_code           VARCHAR(50) NOT NULL,
    action_name           VARCHAR(150) NOT NULL,
    description           VARCHAR(500),
    created_by            UUID REFERENCES users (user_id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID REFERENCES users (user_id),
    updated_at            TIMESTAMPTZ,
    is_deleted            BOOLEAN NOT NULL DEFAULT false,
    deleted_at            TIMESTAMPTZ,
    -- Same action_code (e.g. 'VIEW') is fine under different modules;
    -- only unique WITHIN a module.
    CONSTRAINT uq_permission_actions_module_action UNIQUE (module_id, action_code)
);

CREATE INDEX ix_permission_actions_module_id ON permission_actions (module_id);

-- =====================================================================
-- Seed modules + actions derived from the existing permission_code
-- values ('role.view' -> module ROLE, action VIEW, etc). Handles ANY
-- existing permission_code in the table generically (not just the
-- original 4 seeds), so nothing gets silently dropped.
-- =====================================================================
INSERT INTO permission_modules (module_code, module_name)
SELECT DISTINCT
    UPPER(split_part(p.permission_code, '.', 1))               AS module_code,
    INITCAP(REPLACE(split_part(p.permission_code, '.', 1), '_', ' ')) AS module_name
FROM permissions p
WHERE split_part(p.permission_code, '.', 1) <> ''
ON CONFLICT (module_code) DO NOTHING;

INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT DISTINCT
    pm.module_id,
    UPPER(split_part(p.permission_code, '.', 2))                      AS action_code,
    INITCAP(REPLACE(split_part(p.permission_code, '.', 2), '_', ' ')) AS action_name
FROM permissions p
JOIN permission_modules pm
    ON pm.module_code = UPPER(split_part(p.permission_code, '.', 1))
WHERE split_part(p.permission_code, '.', 2) <> ''
ON CONFLICT (module_id, action_code) DO NOTHING;

-- =====================================================================
-- Duplication guard: if this raises, two different permission_code
-- prefixes normalized to the same (module_code, action_code) pair
-- (e.g. 'Role.View' and 'role.view' as separate rows) - fix the source
-- data before proceeding, since permissions.action_id below assumes a
-- clean 1:1 resolution.
-- =====================================================================
DO $$
DECLARE
    dup_count INT;
BEGIN
    SELECT COUNT(*) INTO dup_count
    FROM (
        SELECT module_id, action_code, COUNT(*) c
        FROM permission_actions
        GROUP BY module_id, action_code
        HAVING COUNT(*) > 1
    ) d;

    IF dup_count > 0 THEN
        RAISE EXCEPTION
            'Duplicate (module_id, action_code) pairs found in permission_actions: %',
            dup_count;
    END IF;
END $$;

-- =====================================================================
-- permissions: link each permission to its action instead of the
-- free-text category column.
-- =====================================================================
ALTER TABLE permissions ADD COLUMN action_id UUID;

UPDATE permissions p
SET action_id = pa.action_id
FROM permission_actions pa
JOIN permission_modules pm ON pm.module_id = pa.module_id
WHERE pm.module_code = UPPER(split_part(p.permission_code, '.', 1))
  AND pa.action_code = UPPER(split_part(p.permission_code, '.', 2));

-- Guard: every permission must have resolved to an action before we
-- enforce NOT NULL. A permission_code without a '.' (e.g. legacy
-- free-form code with no module/action shape) would fail here.
DO $$
DECLARE
    unresolved INT;
BEGIN
    SELECT COUNT(*) INTO unresolved FROM permissions WHERE action_id IS NULL;
    IF unresolved > 0 THEN
        RAISE EXCEPTION
            '% permissions.permission_code values did not resolve to a module.action pair',
            unresolved;
    END IF;
END $$;

ALTER TABLE permissions ALTER COLUMN action_id SET NOT NULL;
ALTER TABLE permissions
    ADD CONSTRAINT fk_permissions_action
    FOREIGN KEY (action_id) REFERENCES permission_actions (action_id);

CREATE INDEX ix_permissions_action_id ON permissions (action_id);

-- category is now fully superseded by permission_actions -> permission_modules.
ALTER TABLE permissions DROP COLUMN category;
