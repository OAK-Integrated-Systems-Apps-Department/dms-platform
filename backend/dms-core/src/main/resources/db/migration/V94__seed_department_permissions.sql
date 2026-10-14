-- Department administration permissions (3.1 Workflow Automation, Phase 1).
--
-- Module OTUA (Organization, Tenant and User Administration) - departments
-- are organisation structure, next to otua.manage-users.*. (IAM is
-- Third-Party Integration, despite the name.) Seeded like V40: granted to
-- no role; TENANT_ADMIN/PLATFORM_ADMIN hold them implicitly, as they do
-- every permission (AuthorizationService).
--
-- Every workflow permission Phase 1 checks already exists:
-- wa.configure-workflow-templates.*, wa.approve-reject-workflow-task.*,
-- wa.view-workflow-audit-trail.view (V10) and dlm.submit-documents-for-review (V10).

INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, 'MANAGE_DEPARTMENTS', 'Manage Departments'
FROM permission_modules pm
WHERE pm.module_code = 'OTUA'
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
            ('otua.manage-departments.view', 'View departments'),
            ('otua.manage-departments.create', 'Create department'),
            ('otua.manage-departments.update', 'Update department'),
            ('otua.manage-departments.delete', 'Delete department')
        ) AS v(permission_code, name)
        JOIN permission_actions pa ON pa.action_code = 'MANAGE_DEPARTMENTS'
        JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'OTUA'
        ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING
        RETURNING 1)
    SELECT count(*) INTO n FROM inserted;
    RAISE NOTICE 'V46: % otua.manage-departments.* permission(s) seeded (granted to no role)', n;
END;
$$;
