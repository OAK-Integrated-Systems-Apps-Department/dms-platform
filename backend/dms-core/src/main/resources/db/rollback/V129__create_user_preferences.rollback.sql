-- Manual rollback ("down") for V129__create_user_preferences.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Forgets every user's interface preferences (editor
-- layout); they fall back to the defaults. Nothing else is touched.
DROP TABLE IF EXISTS user_preferences;
-- Also: DELETE FROM flyway_schema_history WHERE version = '129';
