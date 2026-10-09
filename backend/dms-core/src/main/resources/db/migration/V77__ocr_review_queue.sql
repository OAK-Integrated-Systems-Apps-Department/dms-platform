-- Phase 4b: the review queue (production prompt 3.13).
--
-- "Every failed or requires_review document, with the reason."
--
-- `requires_review` was computed in Phase 3 but lived ONLY in the OCR
-- output artifact in MinIO and in the `.completed` event payload. Both are
-- write-once records of a moment, not queryable state: building a queue
-- from them would mean either fetching every document's artifact out of
-- object storage to filter in memory, or keeping a parallel projection
-- that can drift from the artifact it mirrors. Neither is a queue.
--
-- So the decision it represents is persisted next to `ocr_status`, which
-- is the column it belongs beside: both answer "what state is this
-- version in", both are written by ocr-worker directly in the same
-- transaction as the rest of the outcome, and both are read by dms-core.
ALTER TABLE document_versions
    ADD COLUMN requires_review BOOLEAN NOT NULL DEFAULT false;

-- The reasons, as ocr-worker computed them: missing_mandatory_field,
-- validation_failed, poor_image_quality, file_repaired, plus the detail
-- (which fields, which pages). JSONB rather than a text[] because the
-- detail is structured -- "which mandatory fields" and "which pages" are
-- different shapes -- and a reviewer needs to see WHY without opening the
-- artifact.
--
-- Nullable: a version that has never been through OCR has no opinion,
-- which is different from "reviewed and found clean" (requires_review
-- false with an empty reasons object).
ALTER TABLE document_versions
    ADD COLUMN review_reasons JSONB;

COMMENT ON COLUMN document_versions.requires_review IS
    'Set by ocr-worker per production prompt 3.10: any mandatory field not '
    'extracted, any validation failure, or poor image quality. NOT the same '
    'as low_confidence -- see review.py.';
COMMENT ON COLUMN document_versions.review_reasons IS
    'Why requires_review is true, as {reasons: [...], missing_mandatory: [...], '
    'validation_failures: [...], poor_quality_pages: [...]}. NULL before OCR.';

-- The queue's own read: "everything needing attention in this tenant,
-- newest first". A partial index rather than a full one because the
-- queue is by definition the small minority of rows -- a healthy system
-- has almost none, and indexing the healthy majority would cost writes
-- on every upload to serve a query that never wants them.
CREATE INDEX ix_document_versions_review_queue
    ON document_versions (tenant_id, created_at DESC)
    WHERE is_deleted = false AND (requires_review = true OR ocr_status = 'failed');
