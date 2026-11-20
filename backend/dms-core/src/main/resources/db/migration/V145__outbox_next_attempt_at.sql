-- OBX-03: retry scheduling for the transactional outbox.
--
-- next_attempt_at is the earliest time the relay may try a PENDING event again. The relay computes it after a failed publish
-- (exponential backoff with jitter, see OutboxRetryPolicy) and only selects pending events whose time has come, so a failing
-- event neither hammers the broker every poll nor sits at the head of the queue starving the ones behind it.
--
-- Existing rows: the column is added with DEFAULT now(), which PostgreSQL (11+) stores as a "fast default" - no table rewrite,
-- nothing is locked for long on a large table. Every existing row gets the migration time, so every pending row is eligible
-- immediately; status, attempts, last_error, payload, created_at and published_at are not touched, and first_failed_at is NULL. For published and failed
-- rows the value is meaningless (nothing reads it) and is left as is.
ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- first_failed_at is when the relay first failed to publish the event; the retry window (OutboxRetryPolicy) runs from it, not
-- from created_at, so an event that sat pending for a long time before its first attempt still gets the whole window. NULL means
-- "has not failed yet" - and, for a row that already has attempts from before this migration, "unknown": nothing is invented for
-- those; the first failure the relay observes sets it. Nullable with no default: no table rewrite either.
ALTER TABLE outbox_events
    ADD COLUMN first_failed_at TIMESTAMPTZ;

-- Serves the relay's batch query (status = 'pending' AND next_attempt_at <= now() ORDER BY next_attempt_at, created_at,
-- outbox_event_id LIMIT n FOR UPDATE SKIP LOCKED) as an ordered index scan, and only has entries for pending rows, so it stays
-- small however many published/failed rows accumulate. It does not replace ix_outbox_events_status_created_at (status,
-- created_at), which stays as it is (OBX-04 decides what happens to that one). CREATE INDEX (not CONCURRENTLY) because Flyway
-- runs this in a transaction: it blocks writes to outbox_events for the duration of one scan of the table. That was measured
-- ONLY on disposable data (about 0.3 s for 500,000 rows, in the OBX-03 verification); the time on a production table depends
-- on its size and write load, so review it against the real table before applying. If it is too disruptive, a CONCURRENTLY
-- build needs a separate migration plan and approval.
CREATE INDEX ix_outbox_events_pending_due
    ON outbox_events (next_attempt_at, created_at, outbox_event_id)
    WHERE status = 'pending';

COMMENT ON COLUMN outbox_events.next_attempt_at IS
    'Earliest time the relay may publish this event (meaningful while status = pending). Set by the relay after a failed publish.';
COMMENT ON COLUMN outbox_events.first_failed_at IS
    'When the relay first failed to publish this event (database clock). The retry window runs from here. NULL = not failed yet, or failed before this column existed.';
