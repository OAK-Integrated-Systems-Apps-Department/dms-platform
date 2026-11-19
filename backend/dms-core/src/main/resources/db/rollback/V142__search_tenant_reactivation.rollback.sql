-- Manual rollback ("down") for V142__search_tenant_reactivation.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Trigger first (it calls the function), then the function.
-- Also: DELETE FROM flyway_schema_history WHERE version = '142';
DROP TRIGGER IF EXISTS trg_search_tenants_reactivated ON tenants;
DROP FUNCTION IF EXISTS search_trg_tenants_reactivated();
