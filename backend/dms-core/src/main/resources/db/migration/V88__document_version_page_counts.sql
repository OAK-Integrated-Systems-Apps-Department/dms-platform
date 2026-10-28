-- Pages per stored version, for the dashboard's Digital Impact figures (pages processed
-- digitally, and the paper they stand for). Worked out in the background by PageCountBackfill
-- from the file itself - never estimated from file size:
--   counted  - counted in the file: PDF pages, PowerPoint slide parts, TIFF frames
--   reported - recorded in the file by the program that saved it: Word/OpenDocument page
--              statistics, legacy Office summary information
--   none     - the format has no pages (spreadsheets, images, text, email, archives), or the
--              file couldn't be read; page_count stays null and the version isn't counted
-- pages_counted_at is null until the job has looked at the version.
--
-- page_count itself already exists (V78): ocr-worker sets it early, for the document types it
-- handles, so the review screen's page selector needs no extra read. This migration widens that
-- same column's rules to also cover every OTHER file type PageCountBackfill counts - 0-page
-- formats included, which V78's own "> 0" check never had to allow - and adds the bookkeeping
-- (source, when) neither OCR nor the original column needed on its own. A version OCR already
-- counted still gets picked up here once (pages_counted_at starts null for it too), which simply
-- confirms the same number by a second method rather than double-counting anything.
ALTER TABLE document_versions
    DROP CONSTRAINT ck_document_versions_page_count,
    ADD CONSTRAINT ck_document_versions_page_count CHECK (page_count IS NULL OR page_count >= 0),
    ADD COLUMN page_count_source VARCHAR(20),
    ADD COLUMN pages_counted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_document_versions_page_count_source
        CHECK (page_count_source IS NULL OR page_count_source IN ('counted', 'reported', 'none'));

-- The job's queue: versions not looked at yet.
CREATE INDEX ix_document_versions_pages_pending
    ON document_versions (created_at DESC)
    WHERE pages_counted_at IS NULL AND NOT is_deleted AND purged_at IS NULL;
