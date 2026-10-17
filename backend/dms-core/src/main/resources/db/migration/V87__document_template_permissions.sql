-- Document templates (V69) get permissions of their own, instead of borrowing
-- mdtm.manage-content-types.update (editing the document type itself):
--
--   mdtm.manage-document-templates.create  add a template to a document type
--   mdtm.manage-document-templates.update  rename it, or replace its file
--   mdtm.manage-document-templates.delete  stop offering it
--
-- Seeing, downloading and starting a document from a template still needs only
-- docm.documents.view / docm.documents.create, as before.
--
-- A new action under the existing MDTM (metadata) module, like V29's
-- MANAGE_TYPE_FIELD_MAPPING.
INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, 'MANAGE_DOCUMENT_TEMPLATES', 'Manage Document Templates'
FROM permission_modules pm
WHERE pm.module_code = 'MDTM'
ON CONFLICT (module_id, action_code) DO NOTHING;

INSERT INTO permissions (permission_code, name, description, action_id)
SELECT v.permission_code, v.name, v.description, pa.action_id
FROM (VALUES
    ('mdtm.manage-document-templates.create', 'Add document templates',
     'Add ready-made files (Word, PowerPoint, PDF and more) to a document type for people to start documents from.'),
    ('mdtm.manage-document-templates.update', 'Update document templates',
     'Rename a document type''s templates and replace their files (the old file stays in its version history).'),
    ('mdtm.manage-document-templates.delete', 'Remove document templates',
     'Stop offering a template. Documents already made from it are not affected.')
) AS v(permission_code, name, description)
JOIN permission_actions pa ON pa.action_code = 'MANAGE_DOCUMENT_TEMPLATES'
JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'MDTM'
ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING;

-- Nobody loses what they could do: every role that could manage templates until
-- now - by holding mdtm.manage-content-types.update - gets all three, with the
-- same scope. NOT EXISTS rather than ON CONFLICT: resource_scope may be NULL,
-- which a unique constraint never treats as equal (see V15).
WITH desired_grants AS (
    SELECT rp.tenant_id, rp.role_id, p_new.permission_id, rp.resource_scope
    FROM role_permissions rp
    JOIN permissions p_old ON p_old.permission_id = rp.permission_id
        AND p_old.permission_code = 'mdtm.manage-content-types.update'
    CROSS JOIN permissions p_new
    WHERE p_new.permission_code IN ('mdtm.manage-document-templates.create',
                                    'mdtm.manage-document-templates.update',
                                    'mdtm.manage-document-templates.delete')
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
