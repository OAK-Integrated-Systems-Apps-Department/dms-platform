-- New module: DOCM (Document Management) - acting on documents themselves
-- (upload/view/delete), distinct from MDTM (document-TYPE/metadata-schema
-- management). Same shape as V5's original module+action+permission seed,
-- not an extension of an existing module like V29 was for MDTM.

INSERT INTO permission_modules (module_code, module_name, description)
VALUES ('DOCM', 'Document Management', 'Uploading, viewing, and deleting documents and their versions')
ON CONFLICT (module_code) DO NOTHING;

INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, v.action_code, v.action_name
FROM (VALUES
    ('CREATE', 'Create'),
    ('VIEW', 'View'),
    ('DELETE', 'Delete')
) AS v(action_code, action_name)
JOIN permission_modules pm ON pm.module_code = 'DOCM'
ON CONFLICT (module_id, action_code) DO NOTHING;

INSERT INTO permissions (permission_code, name, action_id)
SELECT v.permission_code, v.name, pa.action_id
FROM (VALUES
    ('docm.documents.create', 'Upload a new document'),
    ('docm.documents.view', 'View/download a document'),
    ('docm.documents.delete', 'Delete a document')
) AS v(permission_code, name)
JOIN permission_actions pa ON pa.action_code = CASE
    WHEN v.permission_code = 'docm.documents.create' THEN 'CREATE'
    WHEN v.permission_code = 'docm.documents.view' THEN 'VIEW'
    ELSE 'DELETE'
END
JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'DOCM'
ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING;
