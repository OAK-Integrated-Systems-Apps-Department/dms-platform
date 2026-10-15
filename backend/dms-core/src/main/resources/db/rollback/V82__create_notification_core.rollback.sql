-- Manual rollback ("down") for V82__create_notification_core.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Run the V84 and
-- V83 rollbacks first (reverse order), then this one. Drops child tables
-- before parents.
DROP TABLE IF EXISTS notification_delivery_attempts;
DROP FUNCTION IF EXISTS notification_attempts_immutable();
DROP TABLE IF EXISTS notification_deliveries;
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS user_notification_preferences;
DROP TABLE IF EXISTS tenant_notification_settings;
DROP TABLE IF EXISTS notification_categories;
