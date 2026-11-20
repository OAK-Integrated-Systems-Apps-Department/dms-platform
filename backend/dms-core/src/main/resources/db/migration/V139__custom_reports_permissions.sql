-- 5.3 Custom Report Generation - new permission codes and the default grants.
--
-- The seven build/run/schedule/template codes already exist (V6, V10). This adds three, seeded like V98:
--
--   crg.configure-reports.manage          tenant admin: settings, field catalogue, delivery rules, official templates,
--                                         schedule governance, and creating schedules with external recipients
--   crg.manage-report-templates.publish   make a template tenant-official (and unpublish it)
--   crg.view-all-runs.view                see the run METADATA of everyone in the tenant (never their files)
--
-- Default grants. Nobody loses or gains access by surprise:
--   * every role that holds docm.documents.view (the permission that gates seeing a document at all) gets
--     crg.generate-custom-reports.run, so "anyone who can see documents can run a report they were given" holds
--     from day one. NOT EXISTS rather than ON CONFLICT: resource_scope may be NULL, which a unique constraint never
--     treats as equal (see V15, V87, V134).
--   * TENANT_ADMIN is a Keycloak realm role that holds every permission implicitly (AuthorizationService), and there is
--     no tenant-administrator row in roles - so there is nothing to backfill for it. The brief's "tenant administrator
--     gets all CRG permissions" is satisfied by that bypass.
--   * Roles granted docm.documents.view LATER get the run grant from RolePermissionServiceImpl#grantPermission
--     (additive hook), so this is not a one-off backfill. New tenants have no roles, so they are covered the same way.

INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, v.action_code, v.action_name
FROM (VALUES
    ('CONFIGURE_REPORTS', 'Configure Reports'),
    ('VIEW_ALL_RUNS', 'View All Report Runs')
) AS v(action_code, action_name)
JOIN permission_modules pm ON pm.module_code = 'CRG'
ON CONFLICT (module_id, action_code) DO NOTHING;

INSERT INTO permissions (permission_code, name, description, action_id)
SELECT v.permission_code, v.name, v.description, pa.action_id
FROM (VALUES
    ('crg.configure-reports.manage', 'Configure reports',
        'Configure the organisation''s reporting: settings, field catalogue, delivery rules, official templates and schedule governance.', 'CONFIGURE_REPORTS'),
    ('crg.manage-report-templates.publish', 'Publish report template',
        'Publish a report template as organisation-official, or unpublish it.', 'MANAGE_REPORT_TEMPLATES'),
    ('crg.view-all-runs.view', 'View all report runs',
        'View the run history (not the files) of every user in the organisation.', 'VIEW_ALL_RUNS')
) AS v(permission_code, name, description, action_code)
JOIN permission_modules pm ON pm.module_code = 'CRG'
JOIN permission_actions pa ON pa.module_id = pm.module_id AND pa.action_code = v.action_code
ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING;

WITH desired_grants AS (
    SELECT rp.tenant_id, rp.role_id, p_new.permission_id, rp.resource_scope
    FROM role_permissions rp
    JOIN permissions p_old ON p_old.permission_id = rp.permission_id
        AND p_old.permission_code = 'docm.documents.view'
    CROSS JOIN permissions p_new
    WHERE p_new.permission_code = 'crg.generate-custom-reports.run'
      AND p_new.tenant_id IS NULL
)
INSERT INTO role_permissions (tenant_id, role_id, permission_id, resource_scope)
SELECT dg.tenant_id, dg.role_id, dg.permission_id, dg.resource_scope
FROM desired_grants dg
WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions existing
    WHERE existing.role_id = dg.role_id
      AND existing.permission_id = dg.permission_id
      AND existing.resource_scope IS NOT DISTINCT FROM dg.resource_scope
);
