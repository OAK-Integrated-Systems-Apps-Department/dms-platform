-- Custom Report Generation (5.3), runs: what a run needs beyond V138.
--   progress_rows        rows written so far, updated in batches while the worker streams - the screen shows it
--   storage_location_id  where the finished file was stored, so a download never has to guess which location the
--                        organisation used when the run was made (a tenant's staging location can change)
-- Both are additive. V138 already indexes what the sweeper and the queue poller read (ix_report_runs_expiry,
-- ix_report_runs_active), so no index is added here.

ALTER TABLE report_runs
    ADD COLUMN progress_rows       BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN storage_location_id UUID REFERENCES storage_locations (storage_location_id);
