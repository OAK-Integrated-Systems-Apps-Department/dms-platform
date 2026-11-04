-- Manual rollback ("down") for V132__retention_scan_markers.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the quick-pass indexes and the last-full-pass marker; the
-- retention job then (after V132 is gone from the code too) walks every document each run, as it did before.
DROP INDEX IF EXISTS idx_documents_never_evaluated;
DROP INDEX IF EXISTS idx_documents_review_due;
DROP INDEX IF EXISTS idx_documents_archive_due;
ALTER TABLE tenant_retention_settings DROP COLUMN IF EXISTS last_full_pass_at;
-- Also: DELETE FROM flyway_schema_history WHERE version = '132';
