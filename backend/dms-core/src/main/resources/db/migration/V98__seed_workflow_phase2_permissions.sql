-- 3.1 Workflow Automation, Phase 2 - new permission codes.
--
-- Seeded like V40/V46: granted to no role; TENANT_ADMIN/PLATFORM_ADMIN hold
-- them implicitly, as they do every permission (AuthorizationService).
--
--   wa.request-workflow-information.create  an approver pauses their task to ask someone for information
--   docm.document-comments.create           write comments (reading needs docm.documents.view)
--
-- Deliberately no code for: delegating (approve/reject plus being the task
-- holder), reassigning (wa.reassign-workflow-task, V10), responding to a
-- request (only the person asked - decision 15, A8) or attaching a
-- supporting document (docm.documents.view - decision 18).

INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, 'REQUEST_WORKFLOW_INFORMATION', 'Request Workflow Information'
FROM permission_modules pm
WHERE pm.module_code = 'WA'
ON CONFLICT (module_id, action_code) DO NOTHING;

INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, 'MANAGE_DOCUMENT_COMMENTS', 'Manage Document Comments'
FROM permission_modules pm
WHERE pm.module_code = 'DOCM'
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
            ('wa.request-workflow-information.create', 'Request information on a workflow task', 'REQUEST_WORKFLOW_INFORMATION', 'WA'),
            ('docm.document-comments.create', 'Comment on documents', 'MANAGE_DOCUMENT_COMMENTS', 'DOCM')
        ) AS v(permission_code, name, action_code, module_code)
        JOIN permission_modules pm ON pm.module_code = v.module_code
        JOIN permission_actions pa ON pa.module_id = pm.module_id AND pa.action_code = v.action_code
        ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING
        RETURNING 1)
    SELECT count(*) INTO n FROM inserted;
    RAISE NOTICE 'V50: % workflow Phase 2 permission(s) seeded (granted to no role)', n;
END;
$$;
