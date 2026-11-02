-- Manual rollback ("down") for V116__add_signature_placement_base_version.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.

ALTER TABLE workflow_tasks DROP COLUMN IF EXISTS signature_placement_base_version_id;
