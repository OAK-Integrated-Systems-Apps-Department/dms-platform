-- Phase 4a: the searchable PDF rendition (production prompt 3.11).
--
-- `renditions` already allowed 'thumbnail' | 'preview' | 'pdf'. A
-- searchable PDF could have been stored as 'pdf' with no migration at
-- all, and that was the tempting option -- but the two are not the same
-- artifact and the difference is exactly what a consumer needs to know.
-- A 'pdf' preview is a picture of the document; a searchable PDF also
-- carries an invisible text layer, so it is the one a full-text index can
-- read and the one a user can Ctrl-F. Conflating them means asking
-- "can I search this rendition?" and having no way to answer.
ALTER TABLE renditions
    DROP CONSTRAINT ck_renditions_type;

ALTER TABLE renditions
    ADD CONSTRAINT ck_renditions_type
        CHECK (rendition_type IN ('thumbnail', 'preview', 'pdf', 'searchable_pdf'));

-- One rendition of each type per version.
--
-- Without this, every OCR re-run appends another row: the version
-- accumulates N searchable PDFs, every reader has to know to take the
-- newest, and each stale row still points at an object nobody deletes.
-- With it, a re-run is an upsert and the table says what is true now --
-- the same current-state shape as document_ocr_field_results and
-- document_ocr_line_items.
--
-- Partial rows from before this migration are collapsed first, keeping
-- the most recent of each (version_id, rendition_type) pair. Safe because
-- nothing writes renditions yet outside this change.
DELETE FROM renditions r
    USING renditions newer
    WHERE r.version_id = newer.version_id
      AND r.rendition_type = newer.rendition_type
      AND (r.created_at < newer.created_at
           OR (r.created_at = newer.created_at AND r.rendition_id < newer.rendition_id));

ALTER TABLE renditions
    ADD CONSTRAINT uq_renditions_version_type UNIQUE (version_id, rendition_type);

COMMENT ON COLUMN renditions.rendition_type IS
    'thumbnail | preview | pdf | searchable_pdf. searchable_pdf is the '
    'original page raster plus an invisible OCR text layer (production '
    'prompt 3.11) -- it looks like the scan and selects like text.';
