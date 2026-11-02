-- 2.8 DRM - the automatic "Duplicate of" link needs the document the user (or the
-- bulk import) decided to keep a copy of, at the moment the new document is created.
--
--   scan_results.duplicate_of_document_id   set by an upload with "Keep both", and by bulk
--                                           import when it adds a separate copy
--
-- A plain column, no FK: the original may be purged later, and the link is only made if
-- the original is still there and visible when the new document appears. Bulk import needs
-- no column of its own - its files go through the same scan gate, so the scan result
-- carries it.

ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS duplicate_of_document_id UUID;
