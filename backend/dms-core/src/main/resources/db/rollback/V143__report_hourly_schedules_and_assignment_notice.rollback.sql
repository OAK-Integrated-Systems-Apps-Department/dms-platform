-- Undoes V143__report_hourly_schedules_and_assignment_notice.sql
-- An hourly schedule cannot exist under the old constraint: they are cancelled, then turned into daily ones so the row stays valid.
UPDATE report_schedules SET status = 'cancelled', pause_reason = 'ROLLBACK_HOURLY', next_run_at = NULL, frequency = 'daily'
WHERE frequency = 'hourly';
ALTER TABLE report_schedules DROP CONSTRAINT ck_report_schedules_frequency;
ALTER TABLE report_schedules
    ADD CONSTRAINT ck_report_schedules_frequency CHECK (frequency IN ('daily', 'weekly', 'monthly'));

DELETE FROM notification_delivery_attempts WHERE notification_id IN
  (SELECT notification_id FROM notifications WHERE category_code = 'report.schedule-assigned');
DELETE FROM notification_deliveries WHERE notification_id IN
  (SELECT notification_id FROM notifications WHERE category_code = 'report.schedule-assigned');
DELETE FROM notifications WHERE category_code = 'report.schedule-assigned';
DELETE FROM tenant_notification_settings WHERE category_code = 'report.schedule-assigned';
DELETE FROM user_notification_preferences WHERE category_code = 'report.schedule-assigned';
DELETE FROM notification_categories WHERE category_code = 'report.schedule-assigned';
-- Also: DELETE FROM flyway_schema_history WHERE version = '143';
