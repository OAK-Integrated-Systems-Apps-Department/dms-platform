-- Bulk import: "Run OCR on imported files", with the same field picker as
-- the interactive scan upload.
--
-- On the BATCH, not on each item. The field selection is one decision the
-- person makes once for the whole import - every row in a batch shares a
-- document type (import_batches.document_type_id), so they necessarily
-- share the set of fields that can be extracted. Storing it per item would
-- be N copies of one answer, and would make "which fields did this import
-- ask for" a query that can return contradictory rows.
--
-- JSONB, not a text[] or a join table, for the same reason
-- scan_results.ocr_requested_fields (V82) is JSONB: this value is handed
-- to ocr-worker verbatim as the message's `requested_fields`, and the
-- round trip is lossless only if the database holds the same JSON array
-- the queue carries. NULL and '[]' both mean "no OCR"; the code treats
-- them identically, so an older batch needs no backfill.
ALTER TABLE import_batches
    ADD COLUMN ocr_requested_fields JSONB;

COMMENT ON COLUMN import_batches.ocr_requested_fields IS
    'JSON array of field_keys to extract from every file in this batch, or '
    'NULL/[] for no OCR. Queued per file only after a clean scan verdict, '
    'on the low-priority queue, within dms.ocr.max-per-import-batch and the '
    'per-tenant queue limit - see OcrEnqueueService.';

-- How many files in this batch have actually been queued for OCR.
--
-- Needed because the batch cap (dms.ocr.max-per-import-batch) is a count
-- per batch, and the only honest place to keep it is next to the batch. An
-- in-memory counter would reset when the worker restarts mid-batch and let
-- a redelivered 5,000-row import queue the cap twice; counting
-- processing_jobs rows instead would be wrong as soon as a finished job is
-- cleaned up.
--
-- It is also what the progress display subtracts from: "OCR queued 500 of
-- 1,200" is this column against total_records.
ALTER TABLE import_batches
    ADD COLUMN ocr_queued_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN import_batches.ocr_queued_count IS
    'Files from this batch queued for OCR so far. Enforces '
    'dms.ocr.max-per-import-batch across worker restarts and redeliveries.';
