-- Manual rollback ("down") for V77__seed_department_permissions.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Removes any role
-- grants of the seeded codes first, then the permissions, then the action.
DELETE FROM role_permissions
WHERE permission_id IN (SELECT permission_id FROM permissions
                        WHERE permission_code LIKE 'otua.manage-departments.%' AND tenant_id IS NULL);
DELETE FROM permissions WHERE permission_code LIKE 'otua.manage-departments.%' AND tenant_id IS NULL;
DELETE FROM permission_actions
WHERE action_code = 'MANAGE_DEPARTMENTS'
  AND module_id = (SELECT module_id FROM permission_modules WHERE module_code = 'OTUA');
