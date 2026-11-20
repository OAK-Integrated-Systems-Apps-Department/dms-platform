-- Manual rollback ("down") for V143__search_admin_run_state.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Only run-state bookkeeping is dropped.
-- Also: DELETE FROM flyway_schema_history WHERE version = '143';
ALTER TABLE search_tenant_state
    DROP COLUMN IF EXISTS reconcile_running_until,
    DROP COLUMN IF EXISTS last_reconcile_requested_at,
    DROP COLUMN IF EXISTS last_reindex_requested_at;
