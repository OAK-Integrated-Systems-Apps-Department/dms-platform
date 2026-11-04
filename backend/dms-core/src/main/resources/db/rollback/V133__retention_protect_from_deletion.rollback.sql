-- Manual rollback ("down") for V133__retention_protect_from_deletion.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the setting; documents can then be deleted regardless of their
-- retention rules again (legal hold still blocks deletion, as before).
ALTER TABLE tenant_retention_settings DROP COLUMN IF EXISTS protect_from_deletion;
-- Also: DELETE FROM flyway_schema_history WHERE version = '133';
