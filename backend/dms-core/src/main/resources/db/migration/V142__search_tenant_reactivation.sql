-- Document Search & Retrieval, Phase 2: a reactivated organisation is searchable again.
--
-- Removing a tenant (is_deleted) enqueues a tenant_purge job that empties its part of the index (V138). Reactivating it
-- (is_deleted back to false) enqueued nothing, so it stayed de-indexed until someone ran a manual reindex. This resets
-- the tenant's search state so the normal backfill re-indexes it: index_ready false (search falls back to title search
-- meanwhile, never an empty "No results"), backfill not started, cursor cleared.
CREATE FUNCTION search_trg_tenants_reactivated() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE search_tenant_state
       SET index_ready = FALSE, backfill_state = 'none', backfill_cursor = NULL, last_backfill_at = NULL, updated_at = now()
     WHERE tenant_id = NEW.tenant_id;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_search_tenants_reactivated AFTER UPDATE ON tenants
    FOR EACH ROW
    WHEN (OLD.is_deleted AND NOT NEW.is_deleted)
    EXECUTE FUNCTION search_trg_tenants_reactivated();
