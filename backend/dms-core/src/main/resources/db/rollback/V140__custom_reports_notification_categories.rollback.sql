-- Manual rollback ("down") for V140__custom_reports_notification_categories.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.
DELETE FROM notification_delivery_attempts WHERE notification_id IN
  (SELECT notification_id FROM notifications WHERE category_code LIKE 'report.%');
DELETE FROM notification_deliveries WHERE notification_id IN
  (SELECT notification_id FROM notifications WHERE category_code LIKE 'report.%');
DELETE FROM notifications WHERE category_code LIKE 'report.%';
DELETE FROM tenant_notification_settings WHERE category_code LIKE 'report.%';
DELETE FROM user_notification_preferences WHERE category_code LIKE 'report.%';
DELETE FROM notification_categories WHERE category_code LIKE 'report.%';
-- Also: DELETE FROM flyway_schema_history WHERE version = '140';
