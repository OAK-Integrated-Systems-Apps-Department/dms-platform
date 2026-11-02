-- Manual rollback ("down") for V113__create_document_ownership.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Run in reverse order (V115, V114, V113).
DROP TABLE IF EXISTS ownership_transfers;
DROP FUNCTION IF EXISTS ownership_transfers_immutable();
DROP TABLE IF EXISTS ownership_transfer_batches;
ALTER TABLE departments DROP COLUMN IF EXISTS holding_owner_user_id;
DROP INDEX IF EXISTS ix_documents_holding;
DROP INDEX IF EXISTS ix_documents_owner_department;
DROP INDEX IF EXISTS ix_documents_tenant_owner;
ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS fk_documents_owner_department,
    DROP CONSTRAINT IF EXISTS ck_documents_ownership_state,
    DROP COLUMN IF EXISTS ownership_state,
    DROP COLUMN IF EXISTS owner_department_id;
