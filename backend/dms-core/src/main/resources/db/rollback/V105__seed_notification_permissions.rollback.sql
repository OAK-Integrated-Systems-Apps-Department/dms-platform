-- Manual rollback ("down") for V105__seed_notification_permissions.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Removes any role
-- grants of the seeded codes first (role_permissions keys on permission_id,
-- like the V98 rollback), then the permissions, then the NOTIF actions and
-- module. Run this before the V104/V103 rollbacks.
DELETE FROM role_permissions
WHERE permission_id IN (SELECT permission_id FROM permissions
                        WHERE permission_code IN ('notif.notification-settings.manage',
                                                  'notif.delivery-log.view',
                                                  'notif.delivery-log.retry')
                          AND tenant_id IS NULL);
DELETE FROM permissions
WHERE permission_code IN ('notif.notification-settings.manage', 'notif.delivery-log.view',
                          'notif.delivery-log.retry')
  AND tenant_id IS NULL;
DELETE FROM permission_actions
WHERE action_code IN ('MANAGE_NOTIFICATION_SETTINGS', 'VIEW_DELIVERY_LOG', 'RETRY_DELIVERY')
  AND module_id = (SELECT module_id FROM permission_modules WHERE module_code = 'NOTIF');
DELETE FROM permission_modules WHERE module_code = 'NOTIF';
