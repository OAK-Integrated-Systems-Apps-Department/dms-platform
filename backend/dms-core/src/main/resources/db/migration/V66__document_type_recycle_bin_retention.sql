-- How long deleted documents of one type stay in the recycle bin, overriding
-- the organisation's own period (recycle_bin_settings, V62) - contracts kept
-- restorable longer than scratch notes, say. NULL = the organisation's period.
ALTER TABLE document_types ADD COLUMN recycle_bin_retention_days INTEGER;
ALTER TABLE document_types ADD CONSTRAINT ck_document_types_recycle_bin_retention
    CHECK (recycle_bin_retention_days IS NULL OR recycle_bin_retention_days BETWEEN 1 AND 3650);
