-- Module 2.1 - File Import/Export: generating bulk exports (FIE-FR-010/011).
--
-- V41 created export_jobs as the record only. Producing the export needs
-- what was asked for, how it went, and where the file is:
--   bulk_selection   export_type for several documents (picked, or matched
--                    by a filter) - the Table Dictionary's types have no
--                    "many documents, no folder" case, and there are no
--                    folders yet
--   metadata_format  xlsx | csv - the metadata sheet inside the ZIP
--   request_params   what to export: documentIds, or documentTypeId +
--                    created-date range, and includeFiles
--   document_count / file_size_bytes / failure_reason / started_at /
--   completed_at     progress and result, for "My exports"
--   storage_location_id  where file_object_key lives (bare FK + index, as V43)
--
-- Every addition has a default, so V41 rows (none outside development)
-- stay valid.

ALTER TABLE export_jobs DROP CONSTRAINT ck_export_jobs_export_type;
ALTER TABLE export_jobs
    ADD CONSTRAINT ck_export_jobs_export_type CHECK (export_type IN
        ('single_document', 'bulk_selection', 'bulk_folder', 'report_data'));

ALTER TABLE export_jobs
    ADD COLUMN metadata_format     VARCHAR(10) NOT NULL DEFAULT 'xlsx',
    ADD COLUMN request_params      JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN document_count      INT NOT NULL DEFAULT 0,
    ADD COLUMN file_size_bytes     BIGINT,
    ADD COLUMN failure_reason      VARCHAR(1000),
    ADD COLUMN started_at          TIMESTAMPTZ,
    ADD COLUMN completed_at        TIMESTAMPTZ,
    ADD COLUMN storage_location_id UUID REFERENCES storage_locations (storage_location_id),
    ADD CONSTRAINT ck_export_jobs_metadata_format CHECK (metadata_format IN ('xlsx', 'csv')),
    ADD CONSTRAINT ck_export_jobs_document_count CHECK (document_count >= 0);

-- "My exports" lists one user's jobs newest first.
CREATE INDEX ix_export_jobs_requested_by_created ON export_jobs (requested_by, created_at DESC);
CREATE INDEX ix_export_jobs_storage_location_id ON export_jobs (storage_location_id);
