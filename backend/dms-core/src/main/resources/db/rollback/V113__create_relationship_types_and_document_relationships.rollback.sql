-- Manual rollback ("down") for V113__create_relationship_types_and_document_relationships.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Run V116, V115, V114 rollbacks first.
-- Children first.
DROP TABLE IF EXISTS document_relationships;
DROP TABLE IF EXISTS relationship_types;
