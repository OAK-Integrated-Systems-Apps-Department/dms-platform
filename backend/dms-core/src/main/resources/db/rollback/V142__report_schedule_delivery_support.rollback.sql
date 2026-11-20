-- Undoes V142__report_schedule_delivery_support.sql
ALTER TABLE report_schedules
    DROP COLUMN IF EXISTS last_failure_code,
    DROP COLUMN IF EXISTS last_failure_slot;

ALTER TABLE report_runs
    DROP COLUMN IF EXISTS delivery_note,
    DROP COLUMN IF EXISTS delivered;

DROP INDEX IF EXISTS ix_rrd_retry;
ALTER TABLE report_run_deliveries
    DROP CONSTRAINT IF EXISTS uq_rrd_target,
    DROP CONSTRAINT IF EXISTS ck_rrd_mode,
    DROP COLUMN IF EXISTS next_attempt_at,
    DROP COLUMN IF EXISTS note,
    DROP COLUMN IF EXISTS delivery_mode,
    DROP COLUMN IF EXISTS recipient_user_id;
