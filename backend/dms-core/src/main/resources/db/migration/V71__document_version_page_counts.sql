-- Pages per stored version, for the dashboard's Digital Impact figures (pages processed
-- digitally, and the paper they stand for). Worked out in the background by PageCountBackfill
-- from the file itself - never estimated from file size:
--   counted  - counted in the file: PDF pages, PowerPoint slide parts, TIFF frames
--   reported - recorded in the file by the program that saved it: Word/OpenDocument page
--              statistics, legacy Office summary information
--   none     - the format has no pages (spreadsheets, images, text, email, archives), or the
--              file couldn't be read; page_count stays null and the version isn't counted
-- pages_counted_at is null until the job has looked at the version.

ALTER TABLE document_versions
    ADD COLUMN page_count INTEGER,
    ADD COLUMN page_count_source VARCHAR(20),
    ADD COLUMN pages_counted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_document_versions_page_count CHECK (page_count IS NULL OR page_count >= 0),
    ADD CONSTRAINT ck_document_versions_page_count_source
        CHECK (page_count_source IS NULL OR page_count_source IN ('counted', 'reported', 'none'));

-- The job's queue: versions not looked at yet.
CREATE INDEX ix_document_versions_pages_pending
    ON document_versions (created_at DESC)
    WHERE pages_counted_at IS NULL AND NOT is_deleted AND purged_at IS NULL;
