-- Manual rollback ("down") for V130__create_records_retention.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the retention rules and settings, and every
-- document lifecycle column - so documents that were Inactive or Archived come back to the normal listings as
-- ordinary (active) documents. Legal holds (V127) are untouched. Role assignments of the four new permissions are removed.
DELETE FROM role_permissions WHERE permission_id IN (SELECT permission_id FROM permissions WHERE permission_code IN
  ('crm.view-archive', 'crm.manage-retention-policies.view', 'crm.review-retention', 'crm.configure-retention'));
DELETE FROM permissions WHERE permission_code IN
  ('crm.view-archive', 'crm.manage-retention-policies.view', 'crm.review-retention', 'crm.configure-retention');
DELETE FROM permission_actions WHERE action_code IN ('VIEW_ARCHIVE', 'REVIEW_RETENTION', 'CONFIGURE_RETENTION')
  AND module_id = (SELECT module_id FROM permission_modules WHERE module_code = 'CRM');
DROP TABLE IF EXISTS tenant_retention_settings;
DROP TABLE IF EXISTS retention_policies;
DROP INDEX IF EXISTS idx_documents_review_flagged;
DROP INDEX IF EXISTS idx_documents_lifecycle;
ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS ck_documents_lifecycle_source,
    DROP CONSTRAINT IF EXISTS ck_documents_lifecycle_state,
    DROP COLUMN IF EXISTS no_policy_flagged_at,
    DROP COLUMN IF EXISTS retention_extended_until,
    DROP COLUMN IF EXISTS review_flagged_at,
    DROP COLUMN IF EXISTS review_due_at,
    DROP COLUMN IF EXISTS archive_due_at,
    DROP COLUMN IF EXISTS retention_policy_id,
    DROP COLUMN IF EXISTS reactivated_at,
    DROP COLUMN IF EXISTS lifecycle_reason,
    DROP COLUMN IF EXISTS lifecycle_change_source,
    DROP COLUMN IF EXISTS lifecycle_changed_by,
    DROP COLUMN IF EXISTS lifecycle_changed_at,
    DROP COLUMN IF EXISTS lifecycle_state;
-- Also: DELETE FROM flyway_schema_history WHERE version = '130';
