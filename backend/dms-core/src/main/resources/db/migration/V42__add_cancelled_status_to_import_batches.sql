-- Module 2.1 - File Import/Export: cancellable bulk imports.
--
-- A user can cancel a batch that is still uploading or queued, which frees
-- their active-batch slot. ck_import_batches_status was declared inline in
-- V39, so it is dropped and recreated with the new value.

ALTER TABLE import_batches DROP CONSTRAINT ck_import_batches_status;

ALTER TABLE import_batches
    ADD CONSTRAINT ck_import_batches_status CHECK (status IN
        ('uploading', 'queued', 'running', 'completed', 'completed_with_errors', 'failed', 'cancelled'));
