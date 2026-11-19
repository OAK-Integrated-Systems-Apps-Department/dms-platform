-- Manual rollback ("down") for V140__create_saved_searches.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the presets (user-authored, not recoverable) and the
-- settings; the indexes go with their tables. Nothing here touches documents or the search index.
-- Also: DELETE FROM flyway_schema_history WHERE version = '140';
DROP TABLE IF EXISTS saved_search_settings;
DROP TABLE IF EXISTS saved_searches;
