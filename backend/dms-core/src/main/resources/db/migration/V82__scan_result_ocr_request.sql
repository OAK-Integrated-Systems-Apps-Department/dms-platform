-- Carry the OCR field selection from upload through to promotion.
--
-- Why it lives on scan_results: OCR cannot start at upload. The file has not
-- been scanned yet, and nothing may read an unscanned file - that is the whole
-- point of the ingestion gate. But the field selection is made at upload, by
-- the person choosing the document type, and with a cloud scanner the verdict
-- may arrive minutes later in a different process with no request behind it.
-- So the choice has to be persisted somewhere that survives that gap, next to
-- the other promotion inputs the gate already carries (title, document_type_id,
-- folder_id).
--
-- It is NOT stored on document_versions at this point for the obvious reason:
-- there is no version yet. The promoter copies it onto the version's own
-- requested_fields (V71) at the moment it creates it, which is also the moment
-- OcrEnqueueService is allowed to queue the job.

ALTER TABLE scan_results
    ADD COLUMN ocr_requested_fields JSONB,
    -- Bulk-import files are queued on a separate, low-priority routing key so
    -- a 5,000-file import cannot starve someone watching a progress bar.
    ADD COLUMN ocr_low_priority BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN scan_results.ocr_requested_fields IS
    'Schema field keys OCR should extract once this file is clean; NULL or [] means no OCR.';
COMMENT ON COLUMN scan_results.ocr_low_priority IS
    'True for bulk-import ingestion: OCR is queued on the low-priority routing key.';

-- The backlog sweeper''s scan: versions whose OCR was requested but never
-- queued, because the batch cap or per-tenant backpressure held them back.
-- Partial so it stays small - the steady state is zero rows.
CREATE INDEX ix_document_versions_ocr_held_back
    ON document_versions (created_at)
    WHERE is_deleted = false
      AND ocr_status = 'pending';
