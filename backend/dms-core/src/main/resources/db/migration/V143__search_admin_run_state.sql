-- Document Search & Retrieval, Phase 2 (S14): admin reindex and reconcile are single-flight and rate limited.
--
-- The admin screen starts a reindex or an on-demand reconcile. While one is running a second request is refused (409),
-- and a new one inside the minimum interval is refused (429). The state lives here, not in memory, so it holds with more
-- than one dms-core instance. reconcile_running_until is a lease: a crashed run frees itself.
ALTER TABLE search_tenant_state
    ADD COLUMN last_reindex_requested_at   TIMESTAMPTZ,
    ADD COLUMN last_reconcile_requested_at TIMESTAMPTZ,
    ADD COLUMN reconcile_running_until     TIMESTAMPTZ;
