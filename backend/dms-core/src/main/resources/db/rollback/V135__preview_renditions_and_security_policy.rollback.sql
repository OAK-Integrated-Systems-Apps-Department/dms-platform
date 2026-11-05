-- Manual rollback ("down") for V135__preview_renditions_and_security_policy.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the preview policy (previews then allow everything, as
-- before) and the thumbnail / failure / partial-preview columns. Thumbnail files already written to storage are not
-- referenced any more; they are removed when their document is purged.
DROP TABLE IF EXISTS tenant_preview_policy;
DROP INDEX IF EXISTS idx_document_versions_rendition_failed;
ALTER TABLE document_versions
    DROP CONSTRAINT IF EXISTS ck_document_versions_thumbnail_status,
    DROP COLUMN IF EXISTS rendition_total_pages,
    DROP COLUMN IF EXISTS rendition_partial,
    DROP COLUMN IF EXISTS rendition_failed_at,
    DROP COLUMN IF EXISTS rendition_failure_reason,
    DROP COLUMN IF EXISTS thumbnail_status,
    DROP COLUMN IF EXISTS thumbnail_object_key;
-- Also: DELETE FROM flyway_schema_history WHERE version = '135';
