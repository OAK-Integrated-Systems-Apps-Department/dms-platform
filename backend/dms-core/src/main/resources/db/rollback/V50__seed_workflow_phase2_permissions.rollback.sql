-- Manual rollback ("down") for V50__seed_workflow_phase2_permissions.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Removes any role
-- grants of the seeded codes first, then the permissions, then the actions.
DELETE FROM role_permissions
WHERE permission_id IN (SELECT permission_id FROM permissions
                        WHERE permission_code IN ('wa.request-workflow-information.create',
                                                  'docm.document-comments.create')
                          AND tenant_id IS NULL);
DELETE FROM permissions
WHERE permission_code IN ('wa.request-workflow-information.create', 'docm.document-comments.create')
  AND tenant_id IS NULL;
DELETE FROM permission_actions
WHERE (action_code = 'REQUEST_WORKFLOW_INFORMATION'
       AND module_id = (SELECT module_id FROM permission_modules WHERE module_code = 'WA'))
   OR (action_code = 'MANAGE_DOCUMENT_COMMENTS'
       AND module_id = (SELECT module_id FROM permission_modules WHERE module_code = 'DOCM'));
