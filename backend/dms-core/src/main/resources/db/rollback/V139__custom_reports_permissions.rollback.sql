-- Manual rollback ("down") for V139__custom_reports_permissions.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.
-- The run grant is removed from every role (it cannot be told apart from one an administrator added by hand).
DELETE FROM role_permissions WHERE permission_id IN
  (SELECT permission_id FROM permissions WHERE permission_code IN
    ('crg.configure-reports.manage', 'crg.manage-report-templates.publish', 'crg.view-all-runs.view', 'crg.generate-custom-reports.run'));
DELETE FROM permissions WHERE tenant_id IS NULL AND permission_code IN
    ('crg.configure-reports.manage', 'crg.manage-report-templates.publish', 'crg.view-all-runs.view');
DELETE FROM permission_actions WHERE action_code IN ('CONFIGURE_REPORTS', 'VIEW_ALL_RUNS')
  AND module_id = (SELECT module_id FROM permission_modules WHERE module_code = 'CRG');
-- Also: DELETE FROM flyway_schema_history WHERE version = '139';
