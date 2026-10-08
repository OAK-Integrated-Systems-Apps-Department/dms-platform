-- The PDF a version is shown as in the browser when it is an Office file
-- (Word .doc, PowerPoint, OpenDocument, RTF, Visio) - made once, on the first
-- preview, by DocumentRenditionService, and kept next to the original in the
-- same document location, encrypted the same way. rendition_status (V32)
-- says how that went: completed, failed or unsupported. Deleted with the
-- version's file when the document is purged (V62).
ALTER TABLE document_versions ADD COLUMN rendition_object_key VARCHAR(1000);
