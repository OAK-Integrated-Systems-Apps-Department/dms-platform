-- Manual rollback ("down") for V131__retention_version_limits_and_notifications.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Drops the version limits from the retention rules, the grace
-- period setting, and the "removed by retention" markers: versions that were hidden by a retention rule come back into
-- the version history (their files are still there unless the grace period had already destroyed them - those stay
-- destroyed, because purged_at is not touched). The three notification categories are removed.
UPDATE document_versions SET is_deleted = false, deleted_at = NULL
 WHERE retention_removed_at IS NOT NULL AND purged_at IS NULL;
DROP INDEX IF EXISTS idx_document_versions_retention_removed;
ALTER TABLE document_versions DROP COLUMN IF EXISTS retention_keep, DROP COLUMN IF EXISTS retention_removed_at;
ALTER TABLE tenant_retention_settings DROP CONSTRAINT IF EXISTS ck_tenant_retention_grace, DROP COLUMN IF EXISTS version_grace_days;
ALTER TABLE retention_policies
    DROP CONSTRAINT IF EXISTS ck_retention_policies_version_age,
    DROP CONSTRAINT IF EXISTS ck_retention_policies_max_versions,
    DROP COLUMN IF EXISTS version_age_unit,
    DROP COLUMN IF EXISTS version_age_amount,
    DROP COLUMN IF EXISTS max_versions;
DELETE FROM notification_delivery_attempts WHERE notification_id IN
  (SELECT notification_id FROM notifications WHERE category_code IN ('retention.review.due', 'retention.no-rule', 'retention.archived.owner'));
DELETE FROM notification_deliveries WHERE notification_id IN
  (SELECT notification_id FROM notifications WHERE category_code IN ('retention.review.due', 'retention.no-rule', 'retention.archived.owner'));
DELETE FROM notifications WHERE category_code IN ('retention.review.due', 'retention.no-rule', 'retention.archived.owner');
DELETE FROM tenant_notification_settings WHERE category_code IN ('retention.review.due', 'retention.no-rule', 'retention.archived.owner');
DELETE FROM user_notification_preferences WHERE category_code IN ('retention.review.due', 'retention.no-rule', 'retention.archived.owner');
DELETE FROM notification_categories WHERE category_code IN ('retention.review.due', 'retention.no-rule', 'retention.archived.owner');
-- Also: DELETE FROM flyway_schema_history WHERE version = '131';
