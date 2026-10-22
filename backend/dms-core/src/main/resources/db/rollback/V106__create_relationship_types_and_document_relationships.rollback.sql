-- Manual rollback ("down") for V106__create_relationship_types_and_document_relationships.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Run V109, V108, V107 rollbacks first.
-- Children first.
DROP TABLE IF EXISTS document_relationships;
DROP TABLE IF EXISTS relationship_types;
