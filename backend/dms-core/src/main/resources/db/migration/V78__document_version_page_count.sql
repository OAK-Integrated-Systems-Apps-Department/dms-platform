-- Phase 4c: the review screen needs to know how many pages to offer
-- before it fetches any of them.
--
-- The page count existed only inside the OCR output artifact in MinIO
-- (as the length of its `pages` array). Reading it from there to render
-- a page selector would mean fetching a whole document's JSON out of
-- object storage to learn one integer -- and for a FAILED document,
-- where there is no artifact at all, it could not be learned at any
-- price, which is exactly the document a reviewer most needs to page
-- through.
--
-- The alternative considered and rejected: ask ocr-worker for page 0's
-- image purely to read its X-Page-Count header, then throw the pixels
-- away. That renders a page to answer a question that was already known
-- at extraction time.
--
-- So it is persisted next to ocr_status, like requires_review before it
-- (V41) and for the same reason: it is a fact about the version that
-- ocr-worker establishes and dms-core reads.
ALTER TABLE document_versions
    ADD COLUMN page_count INT;

COMMENT ON COLUMN document_versions.page_count IS
    'Pages in the source document, set by ocr-worker when it loads the '
    'file. NULL before OCR has run, or for a document that failed before '
    'the file could be opened at all.';

ALTER TABLE document_versions
    ADD CONSTRAINT ck_document_versions_page_count
        CHECK (page_count IS NULL OR page_count > 0);
