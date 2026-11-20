-- OBX-04 (OPTIONAL - include only if the PostgreSQL verification shows the cleanup needs it): a partial index that lets the
-- outbox cleanup pick its next batch of old PUBLISHED rows as an ordered index scan.
--
-- The cleanup (OutboxCleanupStore) selects `status = 'published' AND published_at < cutoff ORDER BY published_at,
-- outbox_event_id LIMIT n FOR UPDATE SKIP LOCKED`. No existing index orders published rows by published_at, so without this one
-- every batch has to read ALL eligible published rows and sort them to find the first n - cost proportional to the backlog, per
-- batch, which is exactly when the backlog is large. With it the batch costs about n index entries.
--
-- Costs: one more index on a hot table. It has entries only for PUBLISHED rows, so with the cleanup running it holds about the
-- retention period's worth of published rows; with the cleanup DISABLED (the default) it grows with every published row for
-- nothing - the reason this is optional and should be applied together with enabling the cleanup. Every publish (the status
-- change) writes one more index entry.
--
-- Migration risk: CREATE INDEX (not CONCURRENTLY, because Flyway runs this in a transaction) blocks writes to outbox_events for
-- the duration of one scan of the table. Measured ONLY on disposable data (see V140OutboxPublishedCleanupIndexMigrationTest);
-- review against the real production table size and write load before applying. A CONCURRENTLY build needs its own migration
-- plan and approval. It does not replace or remove any existing index.
CREATE INDEX ix_outbox_events_published_cleanup
    ON outbox_events (published_at, outbox_event_id)
    WHERE status = 'published';

COMMENT ON INDEX ix_outbox_events_published_cleanup IS
    'OBX-04: serves the outbox cleanup''s ordered batch selection of published rows. Optional; holds published rows only.';
