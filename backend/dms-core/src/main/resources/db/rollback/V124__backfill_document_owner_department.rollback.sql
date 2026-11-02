-- Manual rollback ("down") for V124__backfill_document_owner_department.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. The V123 rollback drops the column anyway;
-- run this only if V124 alone is rolled back (it clears every owner department, including later ones).
UPDATE documents SET owner_department_id = NULL;
