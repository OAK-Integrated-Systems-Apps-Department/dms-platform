-- Version history and the recycle bin.

-- 1. A new version's comment ("what changed"), carried through the scan gate
--    to the version it becomes (document_versions.version_comment).
ALTER TABLE scan_results ADD COLUMN version_comment VARCHAR(1000);

-- 2. Permanent deletion. A deleted document waits in the recycle bin for its
--    tenant's retention period (or until someone empties it), then is purged:
--    every version's file and encryption key is destroyed, its metadata
--    values and access entries removed, and the row kept as a tombstone - the
--    scan results, import rows and audit events that refer to it stay
--    intact (scan result links are immutable by design, V39).
ALTER TABLE documents ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE document_versions ADD COLUMN purged_at TIMESTAMPTZ;
CREATE INDEX idx_documents_recycle_bin ON documents (tenant_id, deleted_at)
    WHERE is_deleted AND purged_at IS NULL;

-- 3. How long a tenant's deleted documents stay restorable. No row = the
--    platform default (dms.documents.recycle-bin.retention).
CREATE TABLE recycle_bin_settings (
    tenant_id       UUID        PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    retention_days  INTEGER     NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID,
    CONSTRAINT ck_recycle_bin_settings_retention CHECK (retention_days BETWEEN 1 AND 3650)
);

-- 4. A document in the recycle bin keeps its own access list (V54), so a
--    restored restricted document comes back to the same people. V61 dropped
--    the list on soft delete; the purge removes it instead.
DROP TRIGGER trg_documents_drop_access_entries ON documents;
DROP FUNCTION drop_access_entries_of_deleted_document();
