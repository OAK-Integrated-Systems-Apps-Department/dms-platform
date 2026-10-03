-- Module 2.1 - File Import/Export: template validation at step 2.
--
-- The filled-in template's rows are checked when the batch is created,
-- before any file is uploaded. A row that fails those checks is saved as
-- failed straight away, with its problems in validation_errors, and is
-- skipped from then on: it never needs a file, can't be matched by an
-- upload, and the worker leaves it as it is. This flag marks those rows
-- explicitly, so they are never confused with rows that failed later (for
-- example because their file never arrived).
--
-- Conventions mirrored from V43/V45: one ALTER TABLE per migration on
-- import_items. NOT NULL with a default, so existing rows read false.

ALTER TABLE import_items
    ADD COLUMN skipped_at_preview BOOLEAN NOT NULL DEFAULT false;
