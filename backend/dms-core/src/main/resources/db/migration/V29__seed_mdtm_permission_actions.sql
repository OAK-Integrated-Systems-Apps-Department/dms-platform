-- Module 2.7 - adds the permission rows the MDTM screens need that weren't
-- part of the original V10 seed: view-only codes (V10 only ever seeded
-- create/update/delete-shaped actions), activate/deactivate/deprecate
-- lifecycle codes for the new field/document-type status transitions, and
-- an entirely new "Manage Type Field Mapping" action for the type<->field
-- mapping screens (A6) - none of which existed anywhere in the catalog.
--
-- Deliberately extends the EXISTING MDTM resource names from V10 rather
-- than introducing new ones - manage-content-types.* for document types,
-- manage-metadata-schema.* for metadata fields - since those rows are
-- already the seeded catalog (see V10/V18) and nothing else in this
-- migration set renames or removes them.

-- =====================================================================
-- New permissions under the existing MANAGE_CONTENT_TYPES action
-- (document-type view + activate/deactivate)
-- =====================================================================
INSERT INTO permissions (permission_code, name, action_id)
SELECT v.permission_code, v.name, pa.action_id
FROM (VALUES
    ('mdtm.manage-content-types.view', 'View content type'),
    ('mdtm.manage-content-types.activate-deactivate', 'Activate / deactivate content type')
) AS v(permission_code, name)
JOIN permission_actions pa ON pa.action_code = 'MANAGE_CONTENT_TYPES'
JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'MDTM'
ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING;

-- =====================================================================
-- New permissions under the existing MANAGE_METADATA_SCHEMA action
-- (metadata-field view + activate/deactivate + deprecate)
-- =====================================================================
INSERT INTO permissions (permission_code, name, action_id)
SELECT v.permission_code, v.name, pa.action_id
FROM (VALUES
    ('mdtm.manage-metadata-schema.view-field', 'View metadata field'),
    ('mdtm.manage-metadata-schema.activate-deactivate-field', 'Activate / deactivate metadata field'),
    ('mdtm.manage-metadata-schema.deprecate-field', 'Deprecate metadata field')
) AS v(permission_code, name)
JOIN permission_actions pa ON pa.action_code = 'MANAGE_METADATA_SCHEMA'
JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'MDTM'
ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING;

-- =====================================================================
-- New action: Manage Type Field Mapping (A6 - document-type <-> field
-- mapping screens), plus its two permissions.
-- =====================================================================
INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, 'MANAGE_TYPE_FIELD_MAPPING', 'Manage Type Field Mapping'
FROM permission_modules pm
WHERE pm.module_code = 'MDTM'
ON CONFLICT (module_id, action_code) DO NOTHING;

INSERT INTO permissions (permission_code, name, action_id)
SELECT v.permission_code, v.name, pa.action_id
FROM (VALUES
    ('mdtm.manage-type-field-mapping.view', 'View document-type field mapping'),
    ('mdtm.manage-type-field-mapping.update', 'Update document-type field mapping')
) AS v(permission_code, name)
JOIN permission_actions pa ON pa.action_code = 'MANAGE_TYPE_FIELD_MAPPING'
JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'MDTM'
ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING;
