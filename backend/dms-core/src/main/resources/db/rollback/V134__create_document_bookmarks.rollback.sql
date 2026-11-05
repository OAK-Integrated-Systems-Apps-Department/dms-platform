-- Manual rollback ("down") for V134__create_document_bookmarks.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the table; the role_permissions
-- grants are left alone, since they're ordinary rows indistinguishable from one an admin added by
-- hand once bd.bookmark-unbookmark-documents.bookmark exists in the catalog, and removing a
-- permission nobody asked to lose is a worse outcome than leaving a now-unused grant in place.
DROP TABLE IF EXISTS document_bookmarks;
-- Also: DELETE FROM flyway_schema_history WHERE version = '134';
