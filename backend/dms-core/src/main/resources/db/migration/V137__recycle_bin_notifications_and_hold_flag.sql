-- Recycle bin follow-ups (RBR-FR-005, RBR-NFR-004).
--
-- 1. Markers so the hourly sweep warns an owner once before the auto-purge, and flags a legal-hold document that
--    has outlived its retention period once - not every hour.
ALTER TABLE documents
    ADD COLUMN recycle_bin_warned_at       TIMESTAMPTZ,
    ADD COLUMN recycle_bin_hold_flagged_at TIMESTAMPTZ;

-- A restore starts a new life: the markers must not carry over to the next deletion.
-- (Cleared by RecycleBinService.restore.)

-- 2. Notification categories (global rows, like V131). Standard: in-app always, email on unless the person turns it off.
INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description) VALUES
 ('recycle-bin.deleted.owner', 'documents', 'standard', false, 'Your document was deleted',
  'A document you own was moved to the recycle bin.'),
 ('recycle-bin.purge-soon.owner', 'documents', 'standard', true, 'Your deleted document will be removed soon',
  'A deleted document you own is about to be permanently removed from the recycle bin.'),
 ('recycle-bin.purged.owner', 'documents', 'standard', false, 'Your deleted document was removed',
  'A deleted document you own was permanently removed from the recycle bin.'),
 ('recycle-bin.hold-review', 'documents', 'standard', true, 'Held documents past their recycle bin period',
  'Deleted documents under legal hold reached the end of their recycle bin period. They were not purged and need a compliance review.')
ON CONFLICT (category_code) DO NOTHING;
