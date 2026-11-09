-- Manual rollback ("down") for V137__recycle_bin_notifications_and_hold_flag.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.
DELETE FROM notification_delivery_attempts WHERE notification_id IN
  (SELECT notification_id FROM notifications WHERE category_code LIKE 'recycle-bin.%');
DELETE FROM notification_deliveries WHERE notification_id IN
  (SELECT notification_id FROM notifications WHERE category_code LIKE 'recycle-bin.%');
DELETE FROM notifications WHERE category_code LIKE 'recycle-bin.%';
DELETE FROM tenant_notification_settings WHERE category_code LIKE 'recycle-bin.%';
DELETE FROM user_notification_preferences WHERE category_code LIKE 'recycle-bin.%';
DELETE FROM notification_categories WHERE category_code LIKE 'recycle-bin.%';
ALTER TABLE documents DROP COLUMN IF EXISTS recycle_bin_warned_at, DROP COLUMN IF EXISTS recycle_bin_hold_flagged_at;
-- Also: DELETE FROM flyway_schema_history WHERE version = '137';
