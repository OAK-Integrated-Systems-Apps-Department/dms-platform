-- Duplicate detection on upload: a file is a duplicate when the same content
-- (SHA-256, already kept per version as file_hash) is in a document the
-- uploader can see, or a file with the same name is in the target folder.

-- Content lookups by hash - the check runs before every upload.
CREATE INDEX idx_document_versions_tenant_hash ON document_versions (tenant_id, file_hash)
    WHERE NOT is_deleted;

-- Bulk import: what to do with a row whose file is already in the DMS, for
-- the whole batch - keep both (no check, as before), add it as a new version
-- of the existing document, or skip it.
ALTER TABLE import_batches ADD COLUMN duplicate_action VARCHAR(20) NOT NULL DEFAULT 'keep_both';
ALTER TABLE import_batches ADD CONSTRAINT ck_import_batches_duplicate_action
    CHECK (duplicate_action IN ('keep_both', 'new_version', 'skip'));

-- The staged file's SHA-256, worked out while it is received, so the row can
-- be checked for duplicates before it becomes a document.
ALTER TABLE import_items ADD COLUMN staged_file_hash VARCHAR(64);
