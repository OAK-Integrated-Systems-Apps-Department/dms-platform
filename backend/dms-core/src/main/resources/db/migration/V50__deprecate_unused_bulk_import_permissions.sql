-- Six FIE codes seeded in V10 were never checked by any @PreAuthorize,
-- and no PermissionCodes constant names them. Bulk import (Module 2.1)
-- runs entirely on fie.bulk-import-documents.upload-file, plus
-- docm.documents.create for the steps that create documents - see
-- BulkImportController. Granting any of these to a custom role was a
-- no-op an admin had no way to discover, the same problem V21 fixed for
-- otua.manage-users.bulk-import:
--   fie.bulk-import-documents.validate-preview - the preview endpoint
--     is read-only and stays on upload-file
--   fie.bulk-import-documents.execute-import   - submit is upload-file
--     + docm.documents.create
--   fie.bulk-import-documents.map-fields       - field mapping comes
--     from the document type's schema; there is no mapping step
--   fie.manage-import-templates.*-template     - templates are
--     generated from the schema on download; none are stored or managed
--
-- Deprecating them (same mechanism as V15 and V21) hides them from
-- new-role permission pickers - see Permission.deprecated and
-- use-permission-catalog.ts's buildPermissionGroups - without deleting
-- the rows, so a role that holds one keeps a visible grant, and a code
-- can be re-enabled by clearing this flag if the capability is built.
--
-- Deliberately untouched: fie.bulk-import-documents.upload-file (in use)
-- and every fie.bulk-export-documents.* code (needed for bulk export).

UPDATE permissions
SET is_deprecated = true
WHERE permission_code IN (
        'fie.bulk-import-documents.validate-preview',
        'fie.bulk-import-documents.execute-import',
        'fie.bulk-import-documents.map-fields',
        'fie.manage-import-templates.create-template',
        'fie.manage-import-templates.update-template',
        'fie.manage-import-templates.delete-template'
    )
  AND tenant_id IS NULL;
