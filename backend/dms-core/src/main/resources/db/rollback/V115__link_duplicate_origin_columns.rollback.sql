-- Manual rollback ("down") for V115__link_duplicate_origin_columns.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.
ALTER TABLE scan_results DROP COLUMN IF EXISTS duplicate_of_document_id;
