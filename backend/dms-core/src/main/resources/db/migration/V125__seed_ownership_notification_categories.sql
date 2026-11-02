-- DOT: notification categories for ownership transfers (global rows, like V109).
INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description) VALUES
 ('document.ownership.received','documents','standard',true,'Document ownership received','Documents were transferred to you.'),
 ('document.ownership.released','documents','optional',false,'Document ownership released','Documents you owned were transferred to someone else.'),
 ('document.ownership.holding','documents','standard',true,'Documents awaiting a new owner','Documents were placed with a holding owner pending reassignment.')
ON CONFLICT (category_code) DO NOTHING;
