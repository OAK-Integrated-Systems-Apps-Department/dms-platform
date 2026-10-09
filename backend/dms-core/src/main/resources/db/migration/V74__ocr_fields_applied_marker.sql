-- Record WHEN a completed OCR run's fields were applied to metadata.
--
-- Phase 2 replaces the poll-time re-apply in DocumentServiceImpl#getOcrStatus
-- (docs/ocr/phase1-closeout.md section 5) with a scheduled reconciler: find
-- versions whose OCR completed but whose document_metadata_values were never
-- written -- a dropped `.completed` event -- apply them, and log a warning
-- naming each one, because each one is a real lost message worth
-- investigating.
--
-- That scan needs to distinguish "never applied" from "applied, and
-- correctly wrote nothing". There is currently no way to: nothing anywhere
-- in the schema records that applyOcrFields ran. Inferring it from
-- document_metadata_values (rows with source='ocr') looks tempting and is
-- wrong, because applyOcrFields legitimately writes nothing in two ordinary
-- cases -- every extracted field is already overridden with source='manual'
-- (DocumentMetadataServiceImpl skips those by design), or every value failed
-- to parse for its field's data type (writeValues is best-effort for
-- source='ocr' and logs a warning). Both of those documents look
-- permanently unapplied to a NOT EXISTS check, so the reconciler would
-- re-apply and re-warn about them on every tick, forever, and the warning
-- would stop meaning anything.
--
-- An explicit marker makes the query exact instead. It is stamped by every
-- caller of applyOcrFields -- the .completed event consumer, the reconciler
-- itself, and (while it still exists) the poll-time re-apply -- so a
-- warning only ever fires for a document no path reached.
--
-- NULL means "not applied yet", which is also the correct value for every
-- existing row: none of them were stamped, and backfilling them to now()
-- would tell the reconciler a lie about documents it has genuinely never
-- looked at.
ALTER TABLE document_versions
    ADD COLUMN ocr_fields_applied_at TIMESTAMPTZ;

COMMENT ON COLUMN document_versions.ocr_fields_applied_at IS
    'When applyOcrFields last ran for this version. NULL = never. Set by '
    'OcrEventsConsumer, OcrFieldsReconciler, and the poll-time re-apply.';

-- Partial, and deliberately narrow: the reconciler only ever scans for
-- completed-but-unapplied versions, which is a tiny and (in a healthy
-- system) empty slice of the table. A full index on ocr_status would be
-- mostly 'not_required' rows the scan never wants.
--
-- document_versions has no index on ocr_status at all today (V32 created
-- only document_id and tenant_id indexes), so without this the reconciler's
-- every-minute tick is a sequential scan of the whole table.
CREATE INDEX IF NOT EXISTS ix_document_versions_ocr_unapplied
    ON document_versions (ocr_status, ocr_fields_applied_at)
    WHERE ocr_status = 'completed' AND ocr_fields_applied_at IS NULL;
