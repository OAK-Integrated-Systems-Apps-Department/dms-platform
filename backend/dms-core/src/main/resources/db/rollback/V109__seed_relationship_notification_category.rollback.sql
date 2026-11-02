-- Manual rollback ("down") for V116__seed_relationship_notification_category.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Notifications already
-- sent keep their category (delivery attempts are append-only), so a category that
-- was used is switched off instead of deleted.
DELETE FROM user_notification_preferences WHERE category_code = 'document.relationship-added';
DELETE FROM tenant_notification_settings WHERE category_code = 'document.relationship-added';
UPDATE notification_categories SET is_active = false
WHERE category_code = 'document.relationship-added'
  AND EXISTS (SELECT 1 FROM notifications WHERE category_code = 'document.relationship-added');
DELETE FROM notification_categories
WHERE category_code = 'document.relationship-added'
  AND NOT EXISTS (SELECT 1 FROM notifications WHERE category_code = 'document.relationship-added');
