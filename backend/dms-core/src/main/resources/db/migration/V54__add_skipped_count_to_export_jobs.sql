-- Module 2.1 - File Import/Export: bulk export skips a document it can't
-- export (no longer accessible, not scanned clean, content unreadable)
-- instead of failing the whole job. document_count is what went into the
-- ZIP; skipped_count is how many of the requested documents were left out
-- (each listed with its reason inside the ZIP).
--
-- Conventions mirrored from V43/V46/V48: one ALTER TABLE per migration.
-- NOT NULL DEFAULT 0, so existing rows read as "nothing skipped".

ALTER TABLE export_jobs
    ADD COLUMN skipped_count INT NOT NULL DEFAULT 0;
