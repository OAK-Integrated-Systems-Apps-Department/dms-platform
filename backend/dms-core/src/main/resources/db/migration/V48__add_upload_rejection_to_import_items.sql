-- Module 2.1 - File Import/Export: intake rejection reasons.
--
-- A file matched to a row can still be refused at intake: its type or
-- content isn't allowed, it is empty or password-protected, or it would
-- take the batch over its size limit. The uploader gets the error at the
-- time, but the row used to fail later as simply "no file uploaded". The
-- last such rejection is kept here (its error code, and a message naming
-- the uploaded file), so the worker can fail the row with the real reason.
-- Both are cleared when a file is accepted for the row, and NULL for every
-- row that never had a file rejected.
--
-- Conventions mirrored from V43/V46: one ALTER TABLE per migration on
-- import_items. Nullable, so existing rows read as never rejected.

ALTER TABLE import_items
    ADD COLUMN upload_rejection_code VARCHAR(50) NULL,
    ADD COLUMN upload_rejection_message TEXT NULL;
