-- Two indexes for when the data gets big. Measured on a scratch copy with 3.7 million documents
-- and 5 million audit events.
--
-- 1. The history of one document. Nothing indexed audit_events.object_id, so opening a
--    document's activity read the whole table: 593 ms at 5M events, 3.5 ms with this.
--    Partial on object_id IS NOT NULL - a quarter of events name no object, and none of them
--    are looked up by it.
--
-- 2. Searching documents by title. The search is lower(title) LIKE '%text%', which no btree can
--    serve, so every search read every document: 7.1 s for a rare phrase and 4.5 s for one that
--    matches nothing, against 0.13 s and 0.7 ms with a trigram index. Partial on the same two
--    conditions the search always carries, so deleted documents and templates are not indexed.
--    Trade-off, measured: a term matching nearly every title ("nvoice" in a set of invoices) is
--    slower with the index (2.6 s) than the ordered scan that stops after 25 rows (0.2 ms). Such
--    searches are rare and still faster than the worst case without it.
--
-- On a database that already holds millions of rows these take a few seconds to build and
-- block writes to the table meanwhile (Flyway runs this in a transaction, so not CONCURRENTLY).
-- pg_trgm is a "trusted" extension: the database owner may create it, no superuser needed.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS ix_audit_events_object
    ON audit_events (tenant_id, object_id, occurred_at DESC)
    WHERE object_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_documents_title_trgm
    ON documents USING gin (lower(title) gin_trgm_ops)
    WHERE NOT is_deleted AND NOT is_template;
