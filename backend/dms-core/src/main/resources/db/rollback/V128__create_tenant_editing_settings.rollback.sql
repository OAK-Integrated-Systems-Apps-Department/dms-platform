-- Manual rollback ("down") for V128__create_tenant_editing_settings.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops each tenant's online-editing on/off and mode;
-- afterwards no tenant can use online editing (the default is off) until the setting is made again. Documents,
-- versions and check-outs are untouched.
DROP TABLE IF EXISTS tenant_editing_settings;
-- Also: DELETE FROM flyway_schema_history WHERE version = '128';
