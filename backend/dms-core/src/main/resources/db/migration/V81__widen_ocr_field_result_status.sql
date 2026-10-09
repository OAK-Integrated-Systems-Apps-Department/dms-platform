-- Let `document_ocr_field_results.status` hold the two statuses ocr-worker
-- has been producing since the accuracy pass but could never write.
--
-- V35 pinned the CHECK to the three statuses that existed then:
-- 'extracted', 'not_found', 'low_confidence'. The worker has since added
-- two more, both of which answer a question the original three cannot:
--
--   not_extractable -- this field is not something a document STATES, so
--       OCR never looked at all. Distinct from `not_found`, which means
--       "we looked and it is not there". Emitted for every field V44
--       marks `metadata_fields.is_ocr_extractable = false`
--       (classification_level, department, cost_centre, tags,
--       description) -- see extraction.py's `_extract_requested_fields`.
--       `value` is always NULL on these rows.
--
--   defaulted -- the page named no currency at all and the tenant default
--       (`OCR_DEFAULT_CURRENCY`) was applied. The value is real and gets
--       filed, but it did not come off the document, so it is never
--       counted as OCR accuracy and is never a review reason -- see
--       extraction.py's `_currency_from_default`.
--
-- Until this ran, either one aborted the worker's entire commit
-- transaction with a CheckViolation. Found live, end to end: an INVOICE
-- upload OCR'd correctly, wrote its searchable-PDF rendition, then rolled
-- back on the first `classification_level` row -- leaving the job
-- `processing` on a 45-minute lease, `document_versions.ocr_status` at
-- `pending`, and the upload screen polling "Queued…" forever with no
-- error ever shown. Every INVOICE upload hit it, because
-- `classification_level` is mandatory for INVOICE and therefore always in
-- `requested_fields`.
--
-- Widened rather than dropped: the constraint is what would have caught a
-- sixth status being invented without the Java and TypeScript unions
-- hearing about it, which is the same drift that produced this.
ALTER TABLE document_ocr_field_results
    DROP CONSTRAINT ck_document_ocr_field_results_status;

ALTER TABLE document_ocr_field_results
    ADD CONSTRAINT ck_document_ocr_field_results_status
    CHECK (status IN ('extracted', 'not_found', 'low_confidence', 'not_extractable', 'defaulted'));
