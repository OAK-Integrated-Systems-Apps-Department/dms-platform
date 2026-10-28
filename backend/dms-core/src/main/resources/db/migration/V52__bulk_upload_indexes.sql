-- Indexes for bulk uploads and the document browser. No data changes.
--
-- ix_import_items_batch_filename: every file uploaded into a bulk import
--   (one by one, from a ZIP, or a resumable upload) looks up the rows naming
--   it - trimmed, ignoring case (ImportItemRepository#findAllByBatchAndFilename).
--   Before, intake loaded the whole batch per file: O(rows x files).
-- ix_import_items_batch_status: the finaliser's and completion job's
--   per-status counts, and the failed-rows report.
-- ix_documents_tenant_folder_created / ix_documents_tenant_created: the
--   document browser's pages (one folder, or all documents), newest first,
--   live documents only - the shape of every DocumentRepository browse query.

CREATE INDEX ix_import_items_batch_filename
    ON import_items (import_batch_id, lower(btrim(filename_reference)));

CREATE INDEX ix_import_items_batch_status
    ON import_items (import_batch_id, status);

CREATE INDEX ix_documents_tenant_folder_created
    ON documents (tenant_id, folder_id, created_at DESC) WHERE NOT is_deleted;

CREATE INDEX ix_documents_tenant_created
    ON documents (tenant_id, created_at DESC) WHERE NOT is_deleted;
