-- Manual rollback ("down") for V136__platform_online_editing_switch.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the platform's per-organisation switch; online editing
-- then depends only on each organisation's own setting (V128), as before - which means an organisation that had it
-- turned on and was switched off by the platform administrator gets it back.
DROP TABLE IF EXISTS tenant_platform_settings;
-- Also: DELETE FROM flyway_schema_history WHERE version = '136';
