-- Module 2.1 - File Import/Export: bulk export runs on
-- fie.bulk-export-documents.execute-export plus docm.documents.view - every
-- endpoint of BulkExportController requires both. Two of the bulk export
-- codes seeded in V10 are never checked by any @PreAuthorize, the same
-- problem V50 fixed for the bulk import codes:
--   fie.bulk-export-documents.select-documents - choosing documents is
--     part of requesting an export; the list of documents a user may pick
--     from is execute-export + docm.documents.view
--   fie.bulk-export-documents.configure-format - the metadata format
--     (xlsx or csv) is a field of the export request, not a separate step
--
-- Deprecating them (same mechanism as V15, V21 and V50) hides them from
-- new-role permission pickers without deleting the rows, so a role that
-- holds one keeps a visible grant, and a code can be re-enabled by
-- clearing this flag if the capability is built.
--
-- Deliberately untouched: fie.bulk-export-documents.execute-export (in use).

UPDATE permissions
SET is_deprecated = true
WHERE permission_code IN (
        'fie.bulk-export-documents.select-documents',
        'fie.bulk-export-documents.configure-format'
    )
  AND tenant_id IS NULL;
