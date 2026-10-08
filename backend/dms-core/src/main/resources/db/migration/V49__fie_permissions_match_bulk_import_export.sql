-- Module 2.1 - File Import/Export: make the FIE permission catalog match
-- what the endpoints actually check, so a tenant admin granting access in
-- the Roles screen sees only codes that do something.
--
-- In use (see PermissionCodes / BulkImportController / ExportJobController /
-- ImportTemplateController):
--   fie.bulk-import-documents.upload-file      every Bulk Import step
--   fie.bulk-export-documents.execute-export   starting a Bulk Export
--                                              (with docm.documents.view)
--   fie.manage-import-templates.*              the tenant's saved import
--                                              templates (V50)
--
-- Deprecated here, like V21 did for otua.manage-users.bulk-import - kept as
-- rows (role_permissions may reference them), never checked by any endpoint:
--   map-fields / validate-preview / execute-import - templates are generated
--     from the schema (nothing to map), checking rows and running the import
--     are part of the same intake the upload-file code already gates
--   select-documents / configure-format - choosing documents and the
--     metadata format are part of the single export request
--
-- No role is granted anything: tenants' roles are their own, and
-- TENANT_ADMIN already holds every permission implicitly.

UPDATE permissions
SET is_deprecated = true
WHERE tenant_id IS NULL
  AND permission_code IN (
      'fie.bulk-import-documents.map-fields',
      'fie.bulk-import-documents.validate-preview',
      'fie.bulk-import-documents.execute-import',
      'fie.bulk-export-documents.select-documents',
      'fie.bulk-export-documents.configure-format');

UPDATE permissions
SET description = 'Use Bulk Import: download templates, upload a filled-in template and its files '
                  || '(one by one or as a ZIP), check the rows, submit or cancel the import, and '
                  || 'download its failed rows.'
WHERE tenant_id IS NULL
  AND permission_code = 'fie.bulk-import-documents.upload-file';

UPDATE permissions
SET description = 'Use Bulk Export: export many documents with their metadata as a ZIP. Also needs '
                  || 'permission to view documents.'
WHERE tenant_id IS NULL
  AND permission_code = 'fie.bulk-export-documents.execute-export';

UPDATE permissions
SET description = 'Create saved import templates: choose a document type''s columns, their order and '
                  || 'headers, and default values for fields left out.'
WHERE tenant_id IS NULL
  AND permission_code = 'fie.manage-import-templates.create-template';

UPDATE permissions
SET description = 'Edit the tenant''s saved import templates.'
WHERE tenant_id IS NULL
  AND permission_code = 'fie.manage-import-templates.update-template';

UPDATE permissions
SET description = 'Delete the tenant''s saved import templates.'
WHERE tenant_id IS NULL
  AND permission_code = 'fie.manage-import-templates.delete-template';
