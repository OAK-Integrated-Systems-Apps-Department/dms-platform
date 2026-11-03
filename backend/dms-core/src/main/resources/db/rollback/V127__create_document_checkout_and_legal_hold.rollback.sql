-- Manual rollback ("down") for V127__create_document_checkout_and_legal_hold.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the current check-out and
-- legal-hold STATE; the audit trail of every check-out/check-in/hold is untouched. A document
-- that is under legal hold when this runs loses the hold - lift it deliberately first if that
-- matters, or restore from the pre-deploy backup instead.
DROP INDEX IF EXISTS idx_documents_checked_out;
ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS ck_documents_legal_hold_consistent,
    DROP CONSTRAINT IF EXISTS ck_documents_checkout_all_or_none,
    DROP COLUMN IF EXISTS legal_hold_reason,
    DROP COLUMN IF EXISTS legal_hold_by,
    DROP COLUMN IF EXISTS legal_hold_at,
    DROP COLUMN IF EXISTS legal_hold,
    DROP COLUMN IF EXISTS checkout_expires_at,
    DROP COLUMN IF EXISTS checked_out_at,
    DROP COLUMN IF EXISTS checked_out_by;
-- Also: DELETE FROM flyway_schema_history WHERE version = '127';
