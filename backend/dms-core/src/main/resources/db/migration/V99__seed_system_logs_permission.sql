-- System monitoring permissions (SHAD - System Health and Administration Dashboard).
--
-- shad.view-system-health-dashboard already exists (V10) and now gates the monitoring screen's
-- figures, alerts and panels. This adds the one thing that was missing: reading service logs,
-- a grant of its own because a log line can carry anything a service printed.
--
-- Unlike V40/V46/V50 these are NOT held implicitly by TENANT_ADMIN: they cover the whole
-- installation, every organisation on it. PLATFORM_ADMIN has them; a tenant user gets them from
-- a role the tenant admin assigns (AuthorizationService#hasGrantedPermission). Seeded granted to
-- no role.

INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, 'VIEW_SYSTEM_LOGS', 'View System Logs'
FROM permission_modules pm
WHERE pm.module_code = 'SHAD'
ON CONFLICT (module_id, action_code) DO NOTHING;

DO
$$
DECLARE
    n bigint;
BEGIN
    WITH inserted AS (
        INSERT INTO permissions (permission_code, name, action_id)
        SELECT 'shad.view-system-logs', 'View system logs', pa.action_id
        FROM permission_actions pa
        JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'SHAD'
        WHERE pa.action_code = 'VIEW_SYSTEM_LOGS'
        ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING
        RETURNING 1)
    SELECT count(*) INTO n FROM inserted;
    RAISE NOTICE 'V82: % shad.view-system-logs permission(s) seeded (granted to no role)', n;
END;
$$;
