-- Undoes V141__report_runs_progress_and_storage.sql
ALTER TABLE report_runs
    DROP COLUMN IF EXISTS storage_location_id,
    DROP COLUMN IF EXISTS progress_rows;
