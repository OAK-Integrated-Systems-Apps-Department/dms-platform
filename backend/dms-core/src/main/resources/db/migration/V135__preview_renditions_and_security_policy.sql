-- 4.2 Document Preview and Rendition Management (DPRM).
--
-- Thumbnails (DPRM-FR-001/002): a small JPEG of the first page of each version, kept beside the original and the
-- preview PDF (V64) - same location, encrypted the same way, deleted with the version's file when the document is
-- purged (V62). thumbnail_status: pending (not made yet - also every version from before this migration, made on
-- first request), completed, failed (the file could not be drawn), unsupported (a type with no picture of its first page).
--
-- A version's renditions are made when the version is stored, in the background (RenditionGenerator), and again on
-- demand when one is missing. They belong to ONE version, so a new version never shows an old version's picture
-- (DPRM-FR-003) - there is nothing to invalidate.
--
-- Failures for administrator review (workflow step 6): why the last attempt failed, and when. rendition_failed_at is
-- null while everything is fine; it is what the administrator's failure list is built from.
--
-- Partial previews (alternate flow): a file past the conversion size limit is converted for its first pages only.
-- rendition_partial says the stored preview PDF is such a cut, rendition_total_pages how long the whole file is when
-- that is known.
ALTER TABLE document_versions
    ADD COLUMN thumbnail_object_key    VARCHAR(1000),
    ADD COLUMN thumbnail_status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    ADD COLUMN rendition_failure_reason VARCHAR(500),
    ADD COLUMN rendition_failed_at     TIMESTAMPTZ,
    ADD COLUMN rendition_partial       BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN rendition_total_pages   INTEGER,
    ADD CONSTRAINT ck_document_versions_thumbnail_status
        CHECK (thumbnail_status IN ('pending', 'completed', 'failed', 'unsupported'));

-- Renditions that had already failed before this migration show up in the failure list too.
UPDATE document_versions
   SET rendition_failed_at = updated_at,
       rendition_failure_reason = 'The file could not be converted for preview.'
 WHERE rendition_status = 'failed';

CREATE INDEX idx_document_versions_rendition_failed
    ON document_versions (tenant_id, rendition_failed_at DESC)
    WHERE rendition_failed_at IS NOT NULL AND NOT is_deleted;

-- DPRM-FR-005: what a user may do from the preview, per organisation. No row = everything allowed (how it has always
-- been). 'watermark' burns the viewer's name and the time into what they print or download; 'block' removes it.
-- Copying text can only be allowed or blocked - there is nothing to watermark.
CREATE TABLE tenant_preview_policy (
    tenant_id     UUID PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    print_mode    VARCHAR(10) NOT NULL DEFAULT 'allow',
    download_mode VARCHAR(10) NOT NULL DEFAULT 'allow',
    copy_mode     VARCHAR(10) NOT NULL DEFAULT 'allow',
    updated_by    UUID,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tenant_preview_policy_print CHECK (print_mode IN ('allow', 'watermark', 'block')),
    CONSTRAINT ck_tenant_preview_policy_download CHECK (download_mode IN ('allow', 'watermark', 'block')),
    CONSTRAINT ck_tenant_preview_policy_copy CHECK (copy_mode IN ('allow', 'block'))
);
