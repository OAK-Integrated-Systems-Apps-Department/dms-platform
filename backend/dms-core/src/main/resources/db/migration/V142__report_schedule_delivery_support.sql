-- Custom Report Generation (5.3), schedules and delivery: what the scheduler and the delivery service need beyond V138.
--
-- report_run_deliveries
--   recipient_user_id  the internal person it went to (a user id is not personal data in itself). An external address is NEVER
--                      stored: a retry finds it again by hash among the schedule's own recipients.
--   delivery_mode      link | attachment - what was actually used, which can differ from the schedule's choice (fallback)
--   note               why it differed or was skipped, e.g. LINK_FALLBACK_TOO_LARGE, RECIPIENT_INACTIVE
--   next_attempt_at    when a pending delivery is tried again (1, 5, 15 minutes)
--   uq_rrd_target      one delivery per run and recipient and channel, so a repeated hand-over cannot send twice
-- report_runs
--   delivered / delivery_note  null = not a delivered run; false + EMPTY = a scheduled report with no data that the organisation
--                              chose not to send (skip_empty_scheduled_reports)
-- report_schedules
--   last_failure_slot / last_failure_code  consecutive failures count per scheduled SLOT, not per file: a slot that produces ten
--                              per-recipient files and fails them all is one failure
-- All additive.

ALTER TABLE report_run_deliveries
    ADD COLUMN recipient_user_id UUID,
    ADD COLUMN delivery_mode     VARCHAR(10),
    ADD COLUMN note              VARCHAR(60),
    ADD COLUMN next_attempt_at   TIMESTAMPTZ,
    ADD CONSTRAINT ck_rrd_mode CHECK (delivery_mode IS NULL OR delivery_mode IN ('link', 'attachment')),
    ADD CONSTRAINT uq_rrd_target UNIQUE (run_id, channel, recipient_type, recipient_ref_hash);
CREATE INDEX ix_rrd_retry ON report_run_deliveries (next_attempt_at) WHERE status = 'pending';

ALTER TABLE report_runs
    ADD COLUMN delivered      BOOLEAN,
    ADD COLUMN delivery_note  VARCHAR(40);

ALTER TABLE report_schedules
    ADD COLUMN last_failure_slot TIMESTAMPTZ,
    ADD COLUMN last_failure_code VARCHAR(60);
