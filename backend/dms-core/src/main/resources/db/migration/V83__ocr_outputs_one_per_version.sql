-- One OCR output row per version, so a retried job overwrites its own result
-- instead of leaving a second one behind.
--
-- dms-core writes these now, not ocr-worker: the worker used to put its output
-- JSON in its own MinIO bucket, outside the encryption layer, under a key
-- nothing in dms-core could find again. OCR output is the document's text -
-- which is to say, the document - so leaving it in plaintext next to an
-- encrypted original defeated the point of encrypting the original. It is
-- stored through the ordinary storage layer now (see OcrArtifactWriter), which
-- means it is encrypted, keyed under the document it belongs to, and reachable
-- by the purge and key-shredding paths.
--
-- The upsert that write depends on needs somewhere to conflict. `renditions`
-- already had uq_renditions_version_type from V76; this is the equivalent for
-- ocr_outputs, which never had one because nothing had ever written a second
-- row for the same version.

-- Collapse any duplicates a pre-merge worker left behind, newest wins. Expected
-- to affect nothing: the old worker wrote one row per run and reruns were rare.
DELETE FROM ocr_outputs o
    USING ocr_outputs newer
    WHERE o.version_id = newer.version_id
      AND o.created_at < newer.created_at;

ALTER TABLE ocr_outputs
    ADD CONSTRAINT uq_ocr_outputs_version UNIQUE (version_id);
