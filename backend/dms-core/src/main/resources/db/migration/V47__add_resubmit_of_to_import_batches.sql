-- Module 2.1 - File Import/Export: resubmitting failed rows.
--
-- A finished import with failed rows can be downloaded as a pre-filled
-- template, fixed, and uploaded again as a new batch. That new batch
-- records the import it came from here, and claims the original rows'
-- still-staged files (rows that only failed on their data) so they don't
-- have to be uploaded twice. NULL for every normal import.
--
-- Conventions mirrored from V43/V45: bare FK column with an index on it.

ALTER TABLE import_batches
    ADD COLUMN resubmit_of_batch_id UUID NULL,
    ADD CONSTRAINT fk_import_batches_resubmit_of FOREIGN KEY (resubmit_of_batch_id)
        REFERENCES import_batches (import_batch_id);

CREATE INDEX ix_import_batches_resubmit_of_batch_id ON import_batches (resubmit_of_batch_id);
