-- 2.8 DRM - tells the owner of a document when someone links to it. Optional tier:
-- email off by default, a user can turn it on. See NotificationService / V103.

INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description)
VALUES ('document.relationship-added', 'documents', 'optional', false, 'Document linked',
        'Someone linked a document to one you own.')
ON CONFLICT (category_code) DO NOTHING;
