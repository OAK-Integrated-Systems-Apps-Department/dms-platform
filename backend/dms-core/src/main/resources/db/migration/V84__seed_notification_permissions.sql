-- 3.2 Notification - new permission module, actions and codes.
--
-- Seeded like V81: granted to no role; TENANT_ADMIN/PLATFORM_ADMIN hold them
-- implicitly, as they do every permission (AuthorizationService). A tenant
-- admin grants them to other roles at runtime.
--
--   notif.notification-settings.manage  tenant-level category email defaults + reminder defaults
--   notif.delivery-log.view             admin delivery log with filters
--   notif.delivery-log.retry            "retry now" on a failed delivery
--
-- Own inbox and own preferences need no permission code - they gate on
-- identity, like /mentions/my and /tasks.

-- The NOTIF module does not exist yet (unlike WA/DOCM in V81), so create it first.
INSERT INTO permission_modules (module_code, module_name, description)
VALUES ('NOTIF', 'Notifications', 'Notification settings and delivery administration')
ON CONFLICT (module_code) DO NOTHING;

INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, v.action_code, v.action_name
FROM (VALUES
    ('MANAGE_NOTIFICATION_SETTINGS', 'Manage Notification Settings'),
    ('VIEW_DELIVERY_LOG',            'View Notification Delivery Log'),
    ('RETRY_DELIVERY',               'Retry Notification Delivery')
) AS v(action_code, action_name)
JOIN permission_modules pm ON pm.module_code = 'NOTIF'
ON CONFLICT (module_id, action_code) DO NOTHING;

DO
$$
DECLARE
    n bigint;
BEGIN
    WITH inserted AS (
        INSERT INTO permissions (permission_code, name, action_id)
        SELECT v.permission_code, v.name, pa.action_id
        FROM (VALUES
            ('notif.notification-settings.manage', 'Manage notification settings', 'MANAGE_NOTIFICATION_SETTINGS'),
            ('notif.delivery-log.view',            'View notification delivery log', 'VIEW_DELIVERY_LOG'),
            ('notif.delivery-log.retry',           'Retry a notification delivery',  'RETRY_DELIVERY')
        ) AS v(permission_code, name, action_code)
        JOIN permission_modules pm ON pm.module_code = 'NOTIF'
        JOIN permission_actions pa ON pa.module_id = pm.module_id AND pa.action_code = v.action_code
        ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING
        RETURNING 1)
    SELECT count(*) INTO n FROM inserted;
    RAISE NOTICE 'V84: % notification permission(s) seeded (granted to no role)', n;
END;
$$;
