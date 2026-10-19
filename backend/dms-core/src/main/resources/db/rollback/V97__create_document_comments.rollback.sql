-- Manual rollback ("down") for V97__create_document_comments.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Children first.
DROP TABLE IF EXISTS comment_mentions;
DROP TABLE IF EXISTS document_comments;
