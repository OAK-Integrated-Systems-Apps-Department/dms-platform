-- "Similar document" detection: a 64-bit SimHash of each version's text
-- (DocumentFingerprints). Two documents whose fingerprints differ in only a
-- few bits have nearly the same text - a re-saved copy, a light edit, the
-- same report under another name. Worked out in the background
-- (FingerprintBackfill) for every version whose text can be read.
--
-- fingerprinted_at: when it was looked at; content_simhash stays NULL when
-- the file has no usable text (an image, a very short file).
ALTER TABLE document_versions ADD COLUMN content_simhash BIGINT;
ALTER TABLE document_versions ADD COLUMN fingerprinted_at TIMESTAMPTZ;
CREATE INDEX idx_document_versions_tenant_simhash ON document_versions (tenant_id)
    WHERE content_simhash IS NOT NULL AND NOT is_deleted;
