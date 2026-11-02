-- Manual rollback ("down") for V125__seed_ownership_notification_categories.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Notifications already sent keep their
-- category, so a category that was used is switched off instead of deleted.
DELETE FROM user_notification_preferences WHERE category_code IN ('document.ownership.received','document.ownership.released','document.ownership.holding');
DELETE FROM tenant_notification_settings WHERE category_code IN ('document.ownership.received','document.ownership.released','document.ownership.holding');
UPDATE notification_categories SET is_active = false
WHERE category_code IN ('document.ownership.received','document.ownership.released','document.ownership.holding')
  AND EXISTS (SELECT 1 FROM notifications n WHERE n.category_code = notification_categories.category_code);
DELETE FROM notification_categories
WHERE category_code IN ('document.ownership.received','document.ownership.released','document.ownership.holding')
  AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.category_code = notification_categories.category_code);
