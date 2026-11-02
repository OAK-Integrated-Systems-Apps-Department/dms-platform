-- Manual rollback ("down") for V112__add_position_to_users.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.

ALTER TABLE users DROP COLUMN IF EXISTS position;
